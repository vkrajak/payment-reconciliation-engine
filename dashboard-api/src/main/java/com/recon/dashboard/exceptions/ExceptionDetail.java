package com.recon.dashboard.exceptions;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Drill-down view: raw events from every source plus a field-by-field comparison. */
public record ExceptionDetail(
        Long id,
        String candidateId,
        String transactionRef,
        String exceptionCode,
        String status,
        String ruleName,
        JsonNode classificationDetail,
        List<String> sourcesPresent,
        JsonNode events,
        List<FieldComparison> comparison,
        Instant firstDetectedAt,
        Instant lastUpdatedAt,
        Instant resolvedAt,
        String resolvedBy,
        String resolutionNote) {

    /** values keyed by source (second event from the same source is keyed "PSP#2", and so on). */
    public record FieldComparison(String field, Map<String, String> valuesBySource, boolean differs) { }
}
