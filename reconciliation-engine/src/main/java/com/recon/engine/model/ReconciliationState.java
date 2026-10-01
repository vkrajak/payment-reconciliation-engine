package com.recon.engine.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Builder;
import lombok.Value;
import lombok.With;
import lombok.extern.jackson.Jacksonized;

/**
 * The aggregate value held in the session-window state store, one instance
 * per (transactionRef, session). Starts empty; {@code TransactionAggregator}
 * fills in whichever field matches the incoming envelope's source. Once all
 * three are non-null ({@link #isComplete()}), the topology hands it to the
 * matching rule chain.
 * <p>
 * Immutable by design ({@code @Value} + {@code @With}) -- Kafka Streams
 * aggregators are expected to return a new value each call, never mutate the
 * previous one in place, since the previous value may still be referenced
 * by the state store's internal bookkeeping.
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

    public static ReconciliationState empty() {
        return ReconciliationState.builder().build();
    }

    public boolean isComplete() {
        return ledger != null && psp != null && bank != null;
    }

    /** Applied by the aggregator: routes the incoming envelope into the matching
     *  field based on its source, leaving the other two untouched. */
    public ReconciliationState withEnvelope(SourceEventEnvelope envelope) {
        ReconciliationState withRef = this.transactionRef == null
                ? this.withTransactionRef(envelope.getTransactionRef())
                : this;
        return switch (envelope.getSource()) {
            case LEDGER -> withRef.withLedger(envelope);
            case PSP -> withRef.withPsp(envelope);
            case BANK -> withRef.withBank(envelope);
        };
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
                .build();
    }
}
