package com.recon.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.recon.events.cache.RedisKeys;
import com.recon.events.cache.TxnStatusCodec;
import com.recon.events.cache.TxnStatusDoc;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real Postgres + real Redis. Kafka listeners are disabled; the SSE endpoint is covered manually (see README). */
@SpringBootTest(properties = {"dashboard.live.matches-enabled=false", "dashboard.metrics.initial-delay-ms=600000"})
@AutoConfigureMockMvc
@Testcontainers
class DashboardApiIT {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine")
            .withInitScript("test-schema.sql");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", pg::getJdbcUrl);
        r.add("spring.datasource.username", pg::getUsername);
        r.add("spring.datasource.password", pg::getPassword);
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    private static final String EVENTS = """
            [{"source":"LEDGER","eventId":"e1","amount":100.00,"currency":"INR","occurredAt":"2026-10-06T06:30:00Z"},
             {"source":"PSP","eventId":"e2","amount":99.50,"currency":"INR","occurredAt":"2026-10-06T06:30:01Z"}]
            """;

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redisTemplate;

    @BeforeEach
    void clean() {
        jdbc.update("TRUNCATE exceptions RESTART IDENTITY");
        jdbc.update("TRUNCATE matched_transactions RESTART IDENTITY");
        redisTemplate.execute((RedisCallback<Object>) c -> {
            c.serverCommands().flushAll();
            return null;
        });
    }

    private long insertException(String ref, String code, String status) {
        return jdbc.queryForObject("""
                INSERT INTO exceptions (candidate_id, transaction_ref, exception_code, sources_present, raw_events,
                                        rule_name, classification_detail, status, first_detected_at)
                VALUES (?, ?, ?, '["LEDGER","PSP"]'::jsonb, ?::jsonb, 'AmountMismatchRule',
                        '{"delta":"0.50"}'::jsonb, ?, '2026-10-06T06:30:00Z'::timestamptz)
                RETURNING id
                """, Long.class, "cand-" + ref + "-" + code, ref, code, EVENTS, status);
    }

    private void insertMatch(String ref, String status, long lagMs) {
        jdbc.update("""
                INSERT INTO matched_transactions (transaction_ref, match_status, ledger_amount, psp_amount,
                                                  bank_amount, currency, settlement_lag_ms, matched_at, business_date)
                VALUES (?, ?, 100.00, 100.00, 100.00, 'INR', ?, '2026-10-06T06:00:00Z'::timestamptz, '2026-10-06')
                """, ref, status, lagMs);
    }

    @Test
    void listFiltersAndPaginates() throws Exception {
        insertException("T1", "AMOUNT_MISMATCH", "OPEN");
        insertException("T2", "AMOUNT_MISMATCH", "OPEN");
        insertException("T3", "MISSING_SOURCE", "RESOLVED");

        mvc.perform(get("/api/v1/exceptions?status=OPEN&size=1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(get("/api/v1/exceptions?code=MISSING_SOURCE"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].transactionRef").value("T3"));
        mvc.perform(get("/api/v1/exceptions?code=BOGUS")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/exceptions?status=NOPE")).andExpect(status().isBadRequest());
    }

    @Test
    void detailShowsSideBySideDiff() throws Exception {
        long id = insertException("T1", "AMOUNT_MISMATCH", "OPEN");

        mvc.perform(get("/api/v1/exceptions/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ruleName").value("AmountMismatchRule"))
                .andExpect(jsonPath("$.events.length()").value(2))
                .andExpect(jsonPath("$.comparison[0].field").value("amount"))
                .andExpect(jsonPath("$.comparison[0].differs").value(true))
                .andExpect(jsonPath("$.comparison[1].field").value("currency"))
                .andExpect(jsonPath("$.comparison[1].differs").value(false));
        mvc.perform(get("/api/v1/exceptions/999999")).andExpect(status().isNotFound());
    }

    @Test
    void resolveWritesAuditTrailAndIsNotRepeatable() throws Exception {
        long id = insertException("T1", "AMOUNT_MISMATCH", "OPEN");
        String body = "{\"resolvedBy\":\"ops.user\",\"note\":\"confirmed with bank\"}";

        mvc.perform(post("/api/v1/exceptions/" + id + "/resolve").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.resolvedBy").value("ops.user"))
                .andExpect(jsonPath("$.resolutionNote").value("confirmed with bank"));

        assertThat(jdbc.queryForObject("SELECT status FROM exceptions WHERE id = ?", String.class, id))
                .isEqualTo("RESOLVED");
        mvc.perform(post("/api/v1/exceptions/" + id + "/resolve").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
    }

    @Test
    void resolveValidatesInput() throws Exception {
        long id = insertException("T1", "AMOUNT_MISMATCH", "OPEN");

        mvc.perform(post("/api/v1/exceptions/" + id + "/resolve").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resolvedBy\":\"ops.user\",\"note\":\"  \"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/exceptions/" + id + "/resolve").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"no author\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/exceptions/" + id + "/resolve").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Resolved-By", "header.user").content("{\"note\":\"author via header\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolvedBy").value("header.user"));
        mvc.perform(post("/api/v1/exceptions/424242/resolve").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resolvedBy\":\"x\",\"note\":\"y\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void transactionLookupIsPostgresFirstTimeThenRedisWithTtl() throws Exception {
        insertMatch("TXN-M1", "MATCHED", 1000);

        mvc.perform(get("/api/v1/transactions/TXN-M1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MATCHED"))
                .andExpect(jsonPath("$.servedFrom").value("DATABASE"))
                .andExpect(jsonPath("$.sources.length()").value(3));
        mvc.perform(get("/api/v1/transactions/TXN-M1"))
                .andExpect(jsonPath("$.servedFrom").value("CACHE"));

        Long ttl = redisTemplate.getExpire(RedisKeys.txnStatus("TXN-M1"));
        assertThat(ttl).isBetween(1L, 86_400L);
        mvc.perform(get("/api/v1/transactions/UNKNOWN")).andExpect(status().isNotFound());
    }

    @Test
    void transactionLookupServesStraightFromRedis() throws Exception {
        var doc = new TxnStatusDoc(TxnStatusDoc.MATCHED, Instant.parse("2026-10-06T06:00:00Z"),
                List.of("LEDGER", "PSP", "BANK"), null, Instant.parse("2026-10-06T06:00:00Z"));
        redisTemplate.opsForValue().set(RedisKeys.txnStatus("TXN-R1"), TxnStatusCodec.toJson(doc));

        mvc.perform(get("/api/v1/transactions/TXN-R1"))   // no rows in Postgres at all
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.servedFrom").value("CACHE"));
    }

    @Test
    void resolvingAnExceptionRefreshesTheCachedStatus() throws Exception {
        long id = insertException("TXN-E1", "AMOUNT_MISMATCH", "OPEN");

        mvc.perform(get("/api/v1/transactions/TXN-E1"))
                .andExpect(jsonPath("$.status").value("EXCEPTION"))
                .andExpect(jsonPath("$.exceptionCode").value("AMOUNT_MISMATCH"));
        mvc.perform(post("/api/v1/exceptions/" + id + "/resolve").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resolvedBy\":\"ops.user\",\"note\":\"done\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/transactions/TXN-E1"))
                .andExpect(jsonPath("$.servedFrom").value("CACHE"))
                .andExpect(jsonPath("$.status").value("RESOLVED"));
    }

    @Test
    void metricsSummaryComputesThenServesFromRedis() throws Exception {
        insertMatch("M1", "MATCHED", 1000);
        insertMatch("M2", "MATCHED_WITH_TOLERANCE", 2000);
        insertMatch("M3", "MATCHED", 3000);
        insertException("E1", "AMOUNT_MISMATCH", "OPEN");   // 2026-10-06 12:00 IST

        mvc.perform(get("/api/v1/metrics/summary?date=2026-10-06"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.servedFrom").value("DATABASE"))
                .andExpect(jsonPath("$.totalTransactions").value(4))
                .andExpect(jsonPath("$.matched").value(3))
                .andExpect(jsonPath("$.matchedWithTolerance").value(1))
                .andExpect(jsonPath("$.exceptions").value(1))
                .andExpect(jsonPath("$.mismatchRatePct").value(25.0))
                .andExpect(jsonPath("$.matchRatePct").value(75.0))
                .andExpect(jsonPath("$.settlementLagP50Ms").value(2000.0))
                .andExpect(jsonPath("$.exceptionsByCode.AMOUNT_MISMATCH").value(1));
        mvc.perform(get("/api/v1/metrics/summary?date=2026-10-06"))
                .andExpect(jsonPath("$.servedFrom").value("CACHE"));

        assertThat(redisTemplate.opsForValue().get(RedisKeys.mismatchRate(java.time.LocalDate.of(2026, 10, 6))))
                .isEqualTo("25.0");
        mvc.perform(get("/api/v1/metrics/summary?date=not-a-date")).andExpect(status().isBadRequest());
    }

    @Test
    void engineHealthReflectsHeartbeats() throws Exception {
        mvc.perform(get("/api/v1/engine/health")).andExpect(jsonPath("$.status").value("DOWN"));

        String now = String.valueOf(System.currentTimeMillis());
        for (int p = 0; p < 3; p++) {
            redisTemplate.opsForValue().set(RedisKeys.heartbeat(p), now);
        }
        mvc.perform(get("/api/v1/engine/health"))
                .andExpect(jsonPath("$.status").value("DEGRADED"))
                .andExpect(jsonPath("$.missingPartitions.length()").value(3));

        for (int p = 3; p < 6; p++) {
            redisTemplate.opsForValue().set(RedisKeys.heartbeat(p), now);
        }
        mvc.perform(get("/api/v1/engine/health"))
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.partitions.length()").value(6));
    }
}
