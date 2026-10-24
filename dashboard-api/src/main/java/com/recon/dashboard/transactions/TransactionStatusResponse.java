package com.recon.dashboard.transactions;

import java.time.Instant;
import java.util.List;

/** servedFrom = CACHE (Redis) or DATABASE (Postgres fallback). */
public record TransactionStatusResponse(
        String transactionRef,
        String status,
        String exceptionCode,
        Instant matchedAt,
        Instant updatedAt,
        List<String> sources,
        String servedFrom) {
}
