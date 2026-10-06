package com.recon.engine.rules;

import com.recon.engine.config.EngineProperties;
import com.recon.engine.model.MatchOutcome;
import com.recon.engine.model.ReconciliationState;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;

/** Second rule in the chain -- see blueprint sec 5.2 rule 2. Only reached if
 *  {@link ExactMatchRule} did not fire, per MatchRuleChain's ordering. */
@Component
@Order(2)
public class ToleranceMatchRule implements MatchRule {

    private final BigDecimal tolerance;

    public ToleranceMatchRule(EngineProperties properties) {
        this.tolerance = properties.getToleranceAmount();
    }

    @Override
    public Optional<MatchOutcome> apply(ReconciliationState state) {
        BigDecimal ledgerAmt = state.getLedger().getAmount();
        BigDecimal pspAmt = state.getPsp().getAmount();
        BigDecimal bankAmt = state.getBank().getAmount();
        String currency = state.getLedger().getCurrency();

        boolean currenciesAgree = currency.equals(state.getPsp().getCurrency())
                && currency.equals(state.getBank().getCurrency());
        if (!currenciesAgree) {
            return Optional.empty(); // currency mismatch is never a tolerance case, it's a hard mismatch
        }

        boolean withinTolerance = diff(ledgerAmt, pspAmt).compareTo(tolerance) <= 0
                && diff(ledgerAmt, bankAmt).compareTo(tolerance) <= 0
                && diff(pspAmt, bankAmt).compareTo(tolerance) <= 0;

        if (withinTolerance) {
            return Optional.of(MatchOutcome.builder()
                    .matchStatus(MatchOutcome.MatchStatus.MATCHED_WITH_TOLERANCE)
                    .ledgerAmount(ledgerAmt)
                    .pspAmount(pspAmt)
                    .bankAmount(bankAmt)
                    .currency(currency)
                    .build());
        }
        return Optional.empty();
    }

    private BigDecimal diff(BigDecimal a, BigDecimal b) {
        return a.subtract(b).abs();
    }
}
