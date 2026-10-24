package com.recon.dashboard.metrics;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recon.dashboard.cache.CacheGateway;
import com.recon.events.cache.RedisKeys;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Cache-aside with a 5-minute TTL. On a hit nothing touches Postgres; on a miss (or Redis outage) the
 * summary is computed from Postgres and written back. Also writes the blueprint's per-metric keys
 * (metrics:mismatch_rate:{date}, metrics:settlement_lag:p50|p95|p99:{date}) for other consumers.
 */
@Service
public class MetricsService {

    private static final Logger log = LoggerFactory.getLogger(MetricsService.class);

    private final CacheGateway cache;
    private final MetricsRepository repo;
    private final ObjectMapper mapper;
    private final ZoneId zone;
    private final Clock clock;

    public MetricsService(CacheGateway cache, MetricsRepository repo, ObjectMapper mapper, ZoneId dashboardZone,
                          Clock dashboardClock) {
        this.cache = cache;
        this.repo = repo;
        this.mapper = mapper;
        this.zone = dashboardZone;
        this.clock = dashboardClock;
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    public MetricsSummary summary(LocalDate date) {
        Optional<String> cached = cache.get(RedisKeys.metricsSummary(date));
        if (cached.isPresent()) {
            try {
                return mapper.readValue(cached.get(), MetricsSummary.class).withServedFrom("CACHE");
            } catch (JsonProcessingException e) {
                log.warn("Corrupt metrics cache entry for {}, recomputing", date);
            }
        }
        MetricsSummary computed = compute(date);
        store(computed);
        return computed;
    }

    /** Used by the scheduled job: always recompute, then overwrite the cache. */
    public void refresh(LocalDate date) {
        store(compute(date));
    }

    MetricsSummary compute(LocalDate date) {
        OffsetDateTime from = date.atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime to = date.plusDays(1).atStartOfDay(zone).toOffsetDateTime();

        MetricsRepository.MatchedAgg matched = repo.matched(date);
        long exceptions = repo.distinctExceptionTxns(from, to);
        long total = matched.total() + exceptions;

        return new MetricsSummary(date, total, matched.total(), matched.withTolerance(), exceptions,
                repo.openExceptions(), pct(matched.total(), total), pct(exceptions, total),
                matched.avgLagMs(), matched.p50LagMs(), matched.p95LagMs(), matched.p99LagMs(),
                repo.exceptionsByCode(from, to), clock.instant(), "DATABASE");
    }

    private void store(MetricsSummary s) {
        try {
            cache.set(RedisKeys.metricsSummary(s.date()), mapper.writeValueAsString(s), RedisKeys.METRICS_TTL);
        } catch (JsonProcessingException e) {
            log.warn("Could not serialise metrics summary: {}", e.toString());
        }
        cache.set(RedisKeys.mismatchRate(s.date()), String.valueOf(s.mismatchRatePct()), RedisKeys.METRICS_TTL);
        setIfPresent(RedisKeys.settlementLag("p50", s.date()), s.settlementLagP50Ms());
        setIfPresent(RedisKeys.settlementLag("p95", s.date()), s.settlementLagP95Ms());
        setIfPresent(RedisKeys.settlementLag("p99", s.date()), s.settlementLagP99Ms());
    }

    private void setIfPresent(String key, Double value) {
        if (value != null) {
            cache.set(key, String.valueOf(value), RedisKeys.METRICS_TTL);
        }
    }

    private static double pct(long part, long total) {
        if (total == 0) {
            return 0.0;
        }
        return BigDecimal.valueOf(part * 100.0 / total).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
