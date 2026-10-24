package com.recon.events.exception;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Published by reconciliation-engine to {@code exception-events} (key = transactionRef).
 * candidateId MUST be deterministic for the same underlying state so redelivery is idempotent.
 */
public record ExceptionCandidate(
        String candidateId,
        String transactionRef,
        CandidateTrigger trigger,
        Instant firstEventAt,
        Instant detectedAt,
        List<SourceSnapshot> events,
        String priorResolution) {

    public Set<SourceType> presentSources() {
        return events.stream().map(SourceSnapshot::source)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(SourceType.class)));
    }

    public Map<SourceType, List<SourceSnapshot>> bySource() {
        Map<SourceType, List<SourceSnapshot>> m = new EnumMap<>(SourceType.class);
        for (SourceSnapshot e : events) {
            m.computeIfAbsent(e.source(), k -> new ArrayList<>()).add(e);
        }
        return m;
    }
}
