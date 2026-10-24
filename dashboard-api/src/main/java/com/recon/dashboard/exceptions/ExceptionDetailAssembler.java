package com.recon.dashboard.exceptions;

import com.fasterxml.jackson.databind.JsonNode;
import com.recon.dashboard.exceptions.ExceptionDetail.FieldComparison;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ExceptionDetailAssembler {

    private static final List<String> FIELDS = List.of("amount", "currency", "occurredAt");

    public ExceptionDetail assemble(ExceptionEntity e) {
        return new ExceptionDetail(e.getId(), e.getCandidateId(), e.getTransactionRef(), e.getExceptionCode(),
                e.getStatus(), e.getRuleName(), e.getClassificationDetail(),
                ExceptionSummary.sources(e.getSourcesPresent()), e.getRawEvents(), compare(e.getRawEvents()),
                e.getFirstDetectedAt(), e.getLastUpdatedAt(), e.getResolvedAt(), e.getResolvedBy(),
                e.getResolutionNote());
    }

    /** Side-by-side diff: for each field, the value each source reported and whether they disagree. */
    List<FieldComparison> compare(JsonNode events) {
        Map<String, JsonNode> byKey = new LinkedHashMap<>();
        Map<String, Integer> seen = new HashMap<>();
        if (events != null && events.isArray()) {
            for (JsonNode ev : events) {
                String source = ev.path("source").asText("UNKNOWN");
                int n = seen.merge(source, 1, Integer::sum);
                byKey.put(n == 1 ? source : source + "#" + n, ev);
            }
        }
        List<FieldComparison> out = new ArrayList<>();
        for (String field : FIELDS) {
            Map<String, String> values = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> entry : byKey.entrySet()) {
                JsonNode v = entry.getValue().get(field);
                if (v != null && !v.isNull()) {
                    values.put(entry.getKey(), v.asText());
                }
            }
            long distinct = values.values().stream().map(v -> normalise(field, v)).distinct().count();
            out.add(new FieldComparison(field, values, distinct > 1));
        }
        return out;
    }

    private static String normalise(String field, String value) {
        if ("amount".equals(field)) {
            try {
                return new BigDecimal(value).stripTrailingZeros().toPlainString();
            } catch (NumberFormatException ignored) {
                return value;
            }
        }
        return value;
    }
}
