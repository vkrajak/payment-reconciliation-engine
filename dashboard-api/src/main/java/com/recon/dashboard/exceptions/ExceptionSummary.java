package com.recon.dashboard.exceptions;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public record ExceptionSummary(
        Long id,
        String transactionRef,
        String exceptionCode,
        String status,
        String ruleName,
        List<String> sourcesPresent,
        Instant firstDetectedAt,
        Instant lastUpdatedAt,
        Instant resolvedAt,
        String resolvedBy) {

    public static ExceptionSummary of(ExceptionEntity e) {
        return new ExceptionSummary(e.getId(), e.getTransactionRef(), e.getExceptionCode(), e.getStatus(),
                e.getRuleName(), sources(e.getSourcesPresent()), e.getFirstDetectedAt(), e.getLastUpdatedAt(),
                e.getResolvedAt(), e.getResolvedBy());
    }

    static List<String> sources(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(n -> out.add(n.asText()));
        }
        return out;
    }
}
