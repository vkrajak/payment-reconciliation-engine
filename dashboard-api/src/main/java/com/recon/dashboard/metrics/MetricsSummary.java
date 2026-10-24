package com.recon.dashboard.metrics;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/**
 * totalTransactions = distinct matched + distinct exception transactions for the day
 * (LATE_ARRIVAL rows are not fresh exceptions and are excluded). Lag values are in milliseconds.
 */
public record MetricsSummary(
        LocalDate date,
        long totalTransactions,
        long matched,
        long matchedWithTolerance,
        long exceptions,
        long openExceptions,
        double matchRatePct,
        double mismatchRatePct,
        Double avgSettlementLagMs,
        Double settlementLagP50Ms,
        Double settlementLagP95Ms,
        Double settlementLagP99Ms,
        Map<String, Long> exceptionsByCode,
        Instant computedAt,
        String servedFrom) {

    public MetricsSummary withServedFrom(String source) {
        return new MetricsSummary(date, totalTransactions, matched, matchedWithTolerance, exceptions, openExceptions,
                matchRatePct, mismatchRatePct, avgSettlementLagMs, settlementLagP50Ms, settlementLagP95Ms,
                settlementLagP99Ms, exceptionsByCode, computedAt, source);
    }
}
