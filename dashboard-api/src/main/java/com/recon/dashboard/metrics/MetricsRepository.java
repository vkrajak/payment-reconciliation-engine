package com.recon.dashboard.metrics;

import java.sql.Date;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Plain SQL on purpose: aggregates and percentile_cont, and no coupling to the matched table's partitioning. */
@Repository
public class MetricsRepository {

    public record MatchedAgg(long total, long withTolerance, Double avgLagMs,
                             Double p50LagMs, Double p95LagMs, Double p99LagMs) { }

    private final NamedParameterJdbcTemplate jdbc;

    public MetricsRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public MatchedAgg matched(LocalDate businessDate) {
        return jdbc.queryForObject("""
                SELECT count(DISTINCT transaction_ref) AS total,
                       count(DISTINCT transaction_ref) FILTER (WHERE match_status = 'MATCHED_WITH_TOLERANCE') AS tol,
                       avg(settlement_lag_ms) AS avg_lag,
                       percentile_cont(0.50) WITHIN GROUP (ORDER BY settlement_lag_ms) AS p50,
                       percentile_cont(0.95) WITHIN GROUP (ORDER BY settlement_lag_ms) AS p95,
                       percentile_cont(0.99) WITHIN GROUP (ORDER BY settlement_lag_ms) AS p99
                  FROM matched_transactions
                 WHERE business_date = :d
                """, new MapSqlParameterSource("d", Date.valueOf(businessDate)), (rs, i) -> {
            return new MatchedAgg(rs.getLong("total"), rs.getLong("tol"),
                    nullableDouble(rs.getDouble("avg_lag"), rs.wasNull()),
                    nullableDouble(rs.getDouble("p50"), rs.wasNull()),
                    nullableDouble(rs.getDouble("p95"), rs.wasNull()),
                    nullableDouble(rs.getDouble("p99"), rs.wasNull()));
        });
    }

    public long distinctExceptionTxns(OffsetDateTime from, OffsetDateTime to) {
        Long n = jdbc.queryForObject("""
                SELECT count(DISTINCT transaction_ref) FROM exceptions
                 WHERE exception_code <> 'LATE_ARRIVAL'
                   AND first_detected_at >= :from AND first_detected_at < :to
                """, window(from, to), Long.class);
        return n == null ? 0 : n;
    }

    public Map<String, Long> exceptionsByCode(OffsetDateTime from, OffsetDateTime to) {
        Map<String, Long> out = new LinkedHashMap<>();
        jdbc.query("""
                SELECT exception_code, count(DISTINCT transaction_ref) AS c FROM exceptions
                 WHERE exception_code <> 'LATE_ARRIVAL'
                   AND first_detected_at >= :from AND first_detected_at < :to
                 GROUP BY exception_code ORDER BY exception_code
                """, window(from, to), rs -> {
            out.put(rs.getString("exception_code"), rs.getLong("c"));
        });
        return out;
    }

    /** All-time OPEN count (the ops backlog), not limited to the selected day. */
    public long openExceptions() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM exceptions WHERE status = 'OPEN'",
                new MapSqlParameterSource(), Long.class);
        return n == null ? 0 : n;
    }

    private static MapSqlParameterSource window(OffsetDateTime from, OffsetDateTime to) {
        return new MapSqlParameterSource().addValue("from", from).addValue("to", to);
    }

    // The double read BEFORE wasNull(): argument evaluation order makes this safe in the lambda above.
    private static Double nullableDouble(double value, boolean wasNull) {
        return wasNull ? null : value;
    }
}
