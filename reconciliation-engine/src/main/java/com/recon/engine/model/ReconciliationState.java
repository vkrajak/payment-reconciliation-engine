package com.recon.engine.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Builder;
import lombok.Value;
import lombok.With;
import lombok.extern.jackson.Jacksonized;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The aggregate value held in the session-window state store, one instance
 * per (transactionRef, session). Starts empty; the aggregator in
 * ReconciliationTopology fills in whichever field matches the incoming
 * envelope's source. Once all three are non-null ({@link #isComplete()}),
 * the topology hands it to the matching rule chain.
 * <p>
 * Immutable by design ({@code @Value} + {@code @With}) -- Kafka Streams
 * aggregators are expected to return a new value each call, never mutate the
 * previous one in place, since the previous value may still be referenced
 * by the state store's internal bookkeeping.
 * <p>
 * PHASE 5 ADDITION: {@code conflict} -- see {@link EnvelopeConflict}'s class
 * doc for the full detection rationale. Only ever holds the MOST RECENT
 * conflict detected for this key; if multiple conflicts occur across the
 * session's lifetime this state only remembers the latest one, which is an
 * accepted simplification for this phase (each conflict is reported as its
 * own exception at detection time by the topology, so none are silently
 * lost -- only the STATE's memory of "the" conflict is single-slot).
 */
@Value
@Builder
@Jacksonized
@With
@JsonIgnoreProperties(ignoreUnknown = true)
public class ReconciliationState {
    String transactionRef;
    SourceEventEnvelope ledger;
    SourceEventEnvelope psp;
    SourceEventEnvelope bank;
    EnvelopeConflict conflict;

    public static ReconciliationState empty() {
        return ReconciliationState.builder().build();
    }

    public boolean isComplete() {
        return ledger != null && psp != null && bank != null;
    }

    public List<String> sourcesPresent() {
        List<String> present = new ArrayList<>();
        if (ledger != null) present.add("LEDGER");
        if (psp != null) present.add("PSP");
        if (bank != null) present.add("BANK");
        return present;
    }

    /**
     * Applied by the aggregator: routes the incoming envelope into the matching
     * field based on its source. If that field already held a DIFFERENT
     * eventId, records an {@link EnvelopeConflict} instead of silently
     * overwriting -- see that class's doc for how the topology uses it.
     * A redelivery of the SAME eventId (the normal at-least-once-delivery
     * case) is treated as a no-op overwrite with identical data, not a conflict.
     */
    public ReconciliationState withEnvelope(SourceEventEnvelope envelope) {
        ReconciliationState withRef = this.transactionRef == null
                ? this.withTransactionRef(envelope.getTransactionRef())
                : this;

        SourceEventEnvelope existing = switch (envelope.getSource()) {
            case LEDGER -> withRef.ledger;
            case PSP -> withRef.psp;
            case BANK -> withRef.bank;
        };

        ReconciliationState updated = switch (envelope.getSource()) {
            case LEDGER -> withRef.withLedger(envelope);
            case PSP -> withRef.withPsp(envelope);
            case BANK -> withRef.withBank(envelope);
        };

        if (existing != null && !Objects.equals(existing.getEventId(), envelope.getEventId())) {
            EnvelopeConflict detected = EnvelopeConflict.builder()
                    .source(envelope.getSource())
                    .existingEventId(existing.getEventId())
                    .newEventId(envelope.getEventId())
                    .detectedAfterComplete(withRef.isComplete())
                    .build();
            return updated.withConflict(detected);
        }
        return updated;
    }

    /** Used by the SessionWindows merger when two sessions for the same key
     *  coalesce (a late event bridges what were two separate sessions). Prefers
     *  whichever side already has a value for each field. */
    public ReconciliationState mergeWith(ReconciliationState other) {
        return ReconciliationState.builder()
                .transactionRef(this.transactionRef != null ? this.transactionRef : other.transactionRef)
                .ledger(this.ledger != null ? this.ledger : other.ledger)
                .psp(this.psp != null ? this.psp : other.psp)
                .bank(this.bank != null ? this.bank : other.bank)
                .conflict(this.conflict != null ? this.conflict : other.conflict)
                .build();
    }
}
