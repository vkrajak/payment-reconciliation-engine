package com.recon.engine.rules;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.recon.engine.model.EnvelopeConflict;
import com.recon.engine.model.ExceptionOutcome;
import com.recon.engine.model.ReconciliationState;
import com.recon.engine.model.SourceEventEnvelope;
import org.springframework.stereotype.Component;

/**
 * Three entry points, one per way a state ends up here instead of
 * MatchRuleChain resolving it cleanly -- see ReconciliationTopology's class
 * doc for which branch of the topology calls each one.
 */
@Component
public class ExceptionClassifier {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    /** Called for a COMPLETE state where MatchRuleChain found no rule fired --
     *  i.e. currency or amount disagree beyond tolerance. */
    public ExceptionOutcome classifyMismatch(ReconciliationState state) {
        boolean currenciesAgree = state.getLedger().getCurrency().equals(state.getPsp().getCurrency())
                && state.getLedger().getCurrency().equals(state.getBank().getCurrency());

        ExceptionOutcome.ExceptionCode code = currenciesAgree
                ? ExceptionOutcome.ExceptionCode.AMOUNT_MISMATCH
                : ExceptionOutcome.ExceptionCode.CURRENCY_MISMATCH;

        return build(code, state);
    }

    /** Called when {@link ReconciliationState#getConflict()} is non-null --
     *  a second, different eventId arrived for a source that already had one. */
    public ExceptionOutcome classifyConflict(ReconciliationState state) {
        EnvelopeConflict conflict = state.getConflict();
        ExceptionOutcome.ExceptionCode code = conflict.isDetectedAfterComplete()
                ? ExceptionOutcome.ExceptionCode.LATE_ARRIVAL
                : ExceptionOutcome.ExceptionCode.DUPLICATE_TRANSACTION_REF;

        return build(code, state);
    }

    /** Called at session-window close (blueprint's suppress-until-window-closes
     *  branch, see ReconciliationTopology) for a state that never became complete. */
    public ExceptionOutcome classifyIncomplete(ReconciliationState state) {
        // Ledger is the source-of-truth producer in this architecture (blueprint
        // diagram: ledger-simulator decides the ground truth for every scenario) --
        // its absence specifically means "we have payment-processor-side evidence
        // of a transaction our own books never recorded", which is categorically
        // different from "the ledger knows about it but a downstream confirmation
        // is just slow/missing". Hence the ledger-null check decides which of the
        // two codes applies, not just "how many sources are missing".
        ExceptionOutcome.ExceptionCode code = state.getLedger() == null
                ? ExceptionOutcome.ExceptionCode.ORPHAN_EVENT
                : ExceptionOutcome.ExceptionCode.MISSING_SOURCE;

        return build(code, state);
    }

    private ExceptionOutcome build(ExceptionOutcome.ExceptionCode code, ReconciliationState state) {
        return ExceptionOutcome.builder()
                .exceptionCode(code)
                .sourcesPresent(state.sourcesPresent())
                .rawLedgerEvent(toJson(state.getLedger()))
                .rawPspEvent(toJson(state.getPsp()))
                .rawBankEvent(toJson(state.getBank()))
                .build();
    }

    private String toJson(SourceEventEnvelope envelope) {
        if (envelope == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(envelope);
        } catch (Exception e) {
            // Audit-trail data only -- a serialization failure here must never block
            // the exception itself from being raised and persisted.
            return "{\"serializationError\":\"" + e.getMessage() + "\"}";
        }
    }
}
