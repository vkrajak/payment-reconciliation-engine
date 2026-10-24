package com.recon.dashboard.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recon.dashboard.cache.CacheGateway;
import com.recon.events.cache.RedisKeys;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MetricsServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 6);
    private final ZoneId zone = ZoneId.of("Asia/Kolkata");
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private CacheGateway cache;
    private MetricsRepository repo;
    private MetricsService service;

    @BeforeEach
    void setUp() {
        cache = mock(CacheGateway.class);
        repo = mock(MetricsRepository.class);
        Clock clock = Clock.fixed(Instant.parse("2026-10-06T08:00:00Z"), zone);
        service = new MetricsService(cache, repo, mapper, zone, clock);

        when(repo.matched(DATE)).thenReturn(new MetricsRepository.MatchedAgg(98, 3, 1200.0, 1000.0, 4000.0, 6000.0));
        when(repo.distinctExceptionTxns(any(), any())).thenReturn(2L);
        when(repo.exceptionsByCode(any(), any())).thenReturn(Map.of("MISSING_SOURCE", 2L));
        when(repo.openExceptions()).thenReturn(5L);
    }

    @Test
    void cacheMissComputesFromPostgresAndWritesEveryKeyWithFiveMinuteTtl() {
        when(cache.get(anyString())).thenReturn(Optional.empty());

        MetricsSummary s = service.summary(DATE);

        assertThat(s.servedFrom()).isEqualTo("DATABASE");
        assertThat(s.totalTransactions()).isEqualTo(100);
        assertThat(s.matchRatePct()).isEqualTo(98.0);
        assertThat(s.mismatchRatePct()).isEqualTo(2.0);
        assertThat(s.openExceptions()).isEqualTo(5);
        verify(cache).set(eq(RedisKeys.mismatchRate(DATE)), eq("2.0"), eq(RedisKeys.METRICS_TTL));
        verify(cache).set(eq(RedisKeys.settlementLag("p95", DATE)), eq("4000.0"), eq(RedisKeys.METRICS_TTL));
        verify(cache).set(eq(RedisKeys.metricsSummary(DATE)), anyString(), eq(RedisKeys.METRICS_TTL));
    }

    @Test
    void cacheHitDoesNotTouchPostgres() throws Exception {
        MetricsSummary stored = new MetricsSummary(DATE, 10, 9, 1, 1, 0, 90.0, 10.0, 1.0, 1.0, 2.0, 3.0,
                Map.of(), Instant.parse("2026-10-06T07:59:00Z"), "DATABASE");
        when(cache.get(RedisKeys.metricsSummary(DATE))).thenReturn(Optional.of(mapper.writeValueAsString(stored)));

        MetricsSummary s = service.summary(DATE);

        assertThat(s.servedFrom()).isEqualTo("CACHE");
        assertThat(s.totalTransactions()).isEqualTo(10);
        verifyNoInteractions(repo);
    }

    @Test
    void zeroTrafficDayGivesZeroRatesNotNaN() {
        when(cache.get(anyString())).thenReturn(Optional.empty());
        when(repo.matched(DATE)).thenReturn(new MetricsRepository.MatchedAgg(0, 0, null, null, null, null));
        when(repo.distinctExceptionTxns(any(), any())).thenReturn(0L);

        MetricsSummary s = service.summary(DATE);

        assertThat(s.matchRatePct()).isZero();
        assertThat(s.mismatchRatePct()).isZero();
        assertThat(s.settlementLagP95Ms()).isNull();
    }

    @Test
    void redisOutageFallsBackToPostgres() {
        // CacheGateway swallows Redis errors and reports a miss; the service must still answer.
        when(cache.get(anyString())).thenReturn(Optional.empty());

        assertThat(service.summary(DATE).servedFrom()).isEqualTo("DATABASE");
    }
}
