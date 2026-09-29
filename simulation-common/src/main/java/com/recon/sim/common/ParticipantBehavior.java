package com.recon.sim.common;

/**
 * How a downstream participant (PSP or bank) should behave for one simulated
 * transaction, as decided by ledger-simulator when it builds the {@link ScenarioPlan}.
 * <p>
 * This directly drives which exception codes the reconciliation-engine will
 * eventually classify (see blueprint sec 6) -- the mapping is intentional:
 *
 * <pre>
 *   EMIT_MATCHING          -&gt; happy path, no exception
 *   EMIT_MISMATCHED_AMOUNT -&gt; AMOUNT_MISMATCH
 *   SKIP                   -&gt; MISSING_SOURCE (if the other two arrive) or ORPHAN_EVENT (rarer combos)
 *   EMIT_DUPLICATE         -&gt; DUPLICATE_TRANSACTION_REF
 *   EMIT_DELAYED           -&gt; SETTLEMENT_LAG_WARNING if still inside the match window,
 *                             LATE_ARRIVAL if it lands after the window already resolved
 * </pre>
 */
public enum ParticipantBehavior {
    EMIT_MATCHING,
    EMIT_MISMATCHED_AMOUNT,
    SKIP,
    EMIT_DUPLICATE,
    EMIT_DELAYED
}
