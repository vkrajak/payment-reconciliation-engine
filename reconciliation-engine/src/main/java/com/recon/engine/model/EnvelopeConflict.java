package com.recon.engine.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/**
 * Set on {@link ReconciliationState} by the aggregator the moment a second
 * envelope for a source that already had one arrives with a DIFFERENT
 * eventId -- i.e. two genuinely distinct events both claiming the same
 * (transactionRef, source) pair, as opposed to the same event being
 * redelivered (same eventId, which the aggregator just treats as a no-op
 * overwrite with identical data).
 * <p>
 * {@code detectedAfterComplete} is what lets the topology distinguish
 * DUPLICATE_TRANSACTION_REF from LATE_ARRIVAL (see
 * rules.ExceptionClassifier#classifyConflict): a conflict seen while the
 * transaction was still incomplete means two competing claims arrived before
 * anything resolved; a conflict seen after all three sources had already
 * been present means a new event showed up for a transactionRef that had
 * already been matched/excepted.
 */
@Value
@Builder
@Jacksonized
@JsonIgnoreProperties(ignoreUnknown = true)
public class EnvelopeConflict {
    SourceType source;
    String existingEventId;
    String newEventId;
    boolean detectedAfterComplete;
}
