package com.recon.events.cache;

import java.time.Duration;
import java.time.LocalDate;

/** Single source of truth for Redis key names and TTLs (blueprint §7.2). */
public final class RedisKeys {

    public static final Duration TXN_STATUS_TTL = Duration.ofHours(24);
    public static final Duration METRICS_TTL = Duration.ofMinutes(5);
    public static final Duration HEARTBEAT_TTL = Duration.ofSeconds(30);
    public static final String HEARTBEAT_PREFIX = "engine:heartbeat:";

    private RedisKeys() { }

    public static String txnStatus(String transactionRef) {
        return "txn:status:" + transactionRef;
    }

    public static String mismatchRate(LocalDate date) {
        return "metrics:mismatch_rate:" + date;
    }

    /** pct is "p50", "p95" or "p99". */
    public static String settlementLag(String pct, LocalDate date) {
        return "metrics:settlement_lag:" + pct + ":" + date;
    }

    /** Full summary JSON used by GET /metrics/summary (not in the blueprint list; avoids 8 separate reads). */
    public static String metricsSummary(LocalDate date) {
        return "metrics:summary:" + date;
    }

    public static String heartbeat(int partition) {
        return HEARTBEAT_PREFIX + partition;
    }
}
