package com.recon.engine.model;

import lombok.Builder;
import lombok.Value;
import lombok.With;

import java.math.BigDecimal;

/**
 * What a MatchRule returns when it fires. Phase 3 only ever produces MATCHED
 * or MATCHED_WITH_TOLERANCE from the rule chain itself; MatchRuleChain may
 * then downgrade the status to SETTLEMENT_LAG_WARNING as a post-processing
 * step (see its class doc for why that's not a fourth competing rule).
 */
@Value
@Builder
@With
public class MatchOutcome {
    MatchStatus matchStatus;
    BigDecimal ledgerAmount;
    BigDecimal pspAmount;
    BigDecimal bankAmount;
    String currency;
    Long settlementLagMs;

    // PHASE 4 ADDITION, audit-only -- carried through to ReconciliationResult's
    // ledgerEventId/pspEventId/bankEventId fields. Not used for dedup (see
    // ReconciliationResult.avsc doc on resultId being the actual idempotency key).
    String ledgerEventId;
    String pspEventId;
    String bankEventId;

    public enum MatchStatus {
        MATCHED, MATCHED_WITH_TOLERANCE, SETTLEMENT_LAG_WARNING
    }
}
