package com.recon.engine.rules;

import com.recon.engine.config.EngineProperties;
import com.recon.engine.model.MatchOutcome;
import com.recon.engine.model.ReconciliationState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * Applies {@link ExactMatchRule} then {@link ToleranceMatchRule} in order
 * (Spring injects the List<MatchRule> pre-sorted by each rule's @Order),
 * first-match-wins, exactly as blueprint sec 5.2 describes.
 * <p>
 * DESIGN NOTE on "rule 3" (settlement-lag / timing-only mismatch): the
 * blueprint describes this as a rule, but it is not actually a THIRD
 * competing strategy in the amount-comparison sense that rules 1 and 2 are
 * -- it's a modifier applied on top of whichever of rules 1/2 already fired.
 * A transaction is never "settlement-lag matched" instead of exact/tolerance
 * matched; it's exact-or-tolerance matched AND ALSO flagged for lag. Modeling
 * it as a post-processing step (rather than a third MatchRule bean in the
 * list) keeps that relationship explicit instead of implying three mutually
 * exclusive strategies.
 * <p>
 * If NEITHER rule 1 nor rule 2 fires (currency mismatch, or amount
 * difference beyond tolerance), this returns empty. Phase 3 has nowhere to
 * route that yet -- ReconciliationTopology logs and drops it, with an
 * explicit TODO pointing at Phase 5 (exception classification).
 */
@Slf4j
@Component
public class MatchRuleChain {

    private final List<MatchRule> rules; // pre-sorted by @Order via Spring's List<T> injection
    private final long settlementLagWarningThresholdMs;

    public MatchRuleChain(List<MatchRule> rules, EngineProperties properties) {
        this.rules = rules;
        this.settlementLagWarningThresholdMs = properties.getSettlementLagWarningThresholdMs();
        log.info("MatchRuleChain initialized with {} rule(s) in order: {}", rules.size(),
                rules.stream().map(r -> r.getClass().getSimpleName()).toList());
    }

    public Optional<MatchOutcome> evaluate(ReconciliationState state) {
        for (MatchRule rule : rules) {
            Optional<MatchOutcome> outcome = rule.apply(state);
            if (outcome.isPresent()) {
                return Optional.of(applySettlementLagCheck(outcome.get(), state));
            }
        }
        return Optional.empty();
    }

    private MatchOutcome applySettlementLagCheck(MatchOutcome outcome, ReconciliationState state) {
        long ledgerPostedAtMs = state.getLedger().getPostedAt().toEpochMilli();
        long bankValueDateMs = state.getBank().getValueDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        long lagMs = Math.abs(ledgerPostedAtMs - bankValueDateMs);

        MatchOutcome withLag = outcome.withSettlementLagMs(lagMs);

        if (lagMs > settlementLagWarningThresholdMs) {
            return withLag.withMatchStatus(MatchOutcome.MatchStatus.SETTLEMENT_LAG_WARNING);
        }
        return withLag;
    }
}
