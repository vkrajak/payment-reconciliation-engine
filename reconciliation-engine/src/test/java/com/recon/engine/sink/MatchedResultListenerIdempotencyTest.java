/*
package com.recon.engine.sink;

import com.recon.events.MatchStatus;
import com.recon.events.ReconciliationResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

*/
/**
 * Exercises {@link MatchedResultListener} against a real Postgres
 * (Testcontainers), not mocks -- the thing actually under test here is SQL
 * behavior (ON CONFLICT semantics, the real unique constraints), which a
 * mocked JdbcTemplate couldn't meaningfully verify.
 * <p>
 * Deliberately NOT a {@code @SpringBootTest}: the full application context
 * includes {@code @EnableKafkaStreams}, which starts a real Kafka Streams
 * client and would need a live Kafka broker just to load the context. This
 * class only needs a DataSource + JdbcTemplate + a transaction manager (so
 * {@code @Transactional} on the listener actually gets proxied -- without a
 * real Spring context that annotation does nothing at all, which would make
 * this test misleadingly "pass" without exercising the real transactional
 * behavior), so {@link TestConfig} imports only those three auto-configurations.
 * <p>
 * Schema note: the table definitions below are a deliberately simplified,
 * non-partitioned version of infra/postgres/init.sql's real DDL. Partitioning
 * is not what Phase 4 is testing -- idempotency is -- so the simpler schema
 * keeps the test focused and fast.
 *//*

@Testcontainers
@SpringJUnitConfig(classes = MatchedResultListenerIdempotencyTest.TestConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MatchedResultListenerIdempotencyTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("recon_test");

    @DynamicPropertySource
    static void dataSourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MatchedResultListener listener;

    @BeforeAll
    void createSchema() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS processed_events (
                    event_id UUID PRIMARY KEY,
                    transaction_ref VARCHAR(64) NOT NULL,
                    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS matched_transactions (
                    id BIGSERIAL PRIMARY KEY,
                    transaction_ref VARCHAR(64) NOT NULL,
                    match_version INT NOT NULL DEFAULT 1,
                    match_status VARCHAR(30) NOT NULL,
                    ledger_amount NUMERIC(18,2),
                    psp_amount NUMERIC(18,2),
                    bank_amount NUMERIC(18,2),
                    currency CHAR(3) NOT NULL,
                    settlement_lag_ms BIGINT,
                    matched_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    business_date DATE NOT NULL,
                    UNIQUE (transaction_ref, match_version, business_date)
                )
                """);
    }

    @BeforeEach
    void resetData() {
        jdbcTemplate.execute("TRUNCATE TABLE processed_events, matched_transactions");
    }

    @Test
    void redeliveredResultWithSameResultIdIsNotDoubleCounted() {
        String transactionRef = "txn-" + UUID.randomUUID();
        String resultId = UUID.randomUUID().toString();
        ReconciliationResult result = sampleResult(transactionRef, resultId, 1);

        // Simulate Kafka's at-least-once delivery redelivering the exact same message
        // (e.g. a consumer restart after committing the DB write but before committing
        // the Kafka offset) by invoking the listener twice with the identical result.
        listener.onReconciliationResult(result);
        listener.onReconciliationResult(result);

        Integer matchedRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM matched_transactions WHERE transaction_ref = ?", Integer.class, transactionRef);
        Integer processedRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM processed_events WHERE transaction_ref = ?", Integer.class, transactionRef);

        assertThat(matchedRows).as("business write must happen exactly once despite redelivery").isEqualTo(1);
        assertThat(processedRows).as("idempotency marker must exist exactly once").isEqualTo(1);
    }

    @Test
    void distinctResultIdsForTheSameTransactionAreBothPersisted() {
        // NOT a redelivery -- this is two genuinely separate results for the same
        // transactionRef, e.g. Phase 5's late-arrival re-evaluation bumping
        // matchVersion. The idempotency layer must NOT conflate "different resultId"
        // with "duplicate"; only an exact resultId match is a duplicate.
        String transactionRef = "txn-" + UUID.randomUUID();
        ReconciliationResult first = sampleResult(transactionRef, UUID.randomUUID().toString(), 1);
        ReconciliationResult second = sampleResult(transactionRef, UUID.randomUUID().toString(), 2);

        listener.onReconciliationResult(first);
        listener.onReconciliationResult(second);

        Integer matchedRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM matched_transactions WHERE transaction_ref = ?", Integer.class, transactionRef);
        Integer processedRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM processed_events WHERE transaction_ref = ?", Integer.class, transactionRef);

        assertThat(matchedRows).as("two distinct results must both be persisted, not deduped against each other").isEqualTo(2);
        assertThat(processedRows).isEqualTo(2);
    }

    private ReconciliationResult sampleResult(String transactionRef, String resultId, int matchVersion) {
        return ReconciliationResult.newBuilder()
                .setTransactionRef(transactionRef)
                .setMatchStatus(MatchStatus.MATCHED)
                .setCurrency("INR")
                .setMatchedAt(Instant.now())
                .setMatchVersion(matchVersion)
                .setResultId(resultId)
                .build();
    }

    @Configuration
    @EnableTransactionManagement
    @Import({DataSourceAutoConfiguration.class, JdbcTemplateAutoConfiguration.class, DataSourceTransactionManagerAutoConfiguration.class})
    static class TestConfig {
        @Bean
        MatchedResultListener matchedResultListener(JdbcTemplate jdbcTemplate) {
            return new MatchedResultListener(jdbcTemplate);
        }
    }
}
*/
