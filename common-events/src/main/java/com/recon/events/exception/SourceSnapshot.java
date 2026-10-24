package com.recon.events.exception;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;

public record SourceSnapshot(
        SourceType source,
        String eventId,
        BigDecimal amount,
        String currency,
        Instant occurredAt,
        JsonNode raw) {
}
