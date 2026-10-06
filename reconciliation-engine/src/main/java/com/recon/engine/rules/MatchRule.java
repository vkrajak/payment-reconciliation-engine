package com.recon.engine.rules;

import com.recon.engine.model.MatchOutcome;
import com.recon.engine.model.ReconciliationState;

import java.util.Optional;

/**
 * One rule in the ordered chain (blueprint sec 5.2). Only ever invoked once
 * {@link ReconciliationState#isComplete()} is true -- Phase 3 has no concept
 * of "resolve with 2 of 3 sources", that's exception territory (Phase 5).
 * Returns empty to mean "this rule doesn't apply, try the next one",
 * never to mean "match rejected forever" -- that distinction matters once
 * Phase 5 adds an exception rule at the end of the chain.
 */
public interface MatchRule {
    Optional<MatchOutcome> apply(ReconciliationState state);
}
