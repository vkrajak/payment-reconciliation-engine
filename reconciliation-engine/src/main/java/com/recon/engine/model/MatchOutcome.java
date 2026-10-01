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

    public enum MatchStatus {
        MATCHED, MATCHED_WITH_TOLERANCE, SETTLEMENT_LAG_WARNING
    }
}
