package com.recon.engine.rules;

import com.recon.engine.model.MatchOutcome;
import com.recon.engine.model.ReconciliationState;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;

/** First rule in the chain -- see blueprint sec 5.2 rule 1. */
@Component
@Order(1)
public class ExactMatchRule implements MatchRule {

    @Override
    public Optional<MatchOutcome> apply(ReconciliationState state) {
        BigDecimal ledgerAmt = state.getLedger().getAmount();
        BigDecimal pspAmt = state.getPsp().getAmount();
        BigDecimal bankAmt = state.getBank().getAmount();
        String currency = state.getLedger().getCurrency();

        boolean currenciesAgree = currency.equals(state.getPsp().getCurrency())
                && currency.equals(state.getBank().getCurrency());

        // compareTo (not equals) so 100.50 and 100.5 are still "exact" --
        // BigDecimal.equals() is scale-sensitive, compareTo() is value-sensitive.
        boolean amountsExact = ledgerAmt.compareTo(pspAmt) == 0 && ledgerAmt.compareTo(bankAmt) == 0;

        if (currenciesAgree && amountsExact) {
            return Optional.of(MatchOutcome.builder()
                    .matchStatus(MatchOutcome.MatchStatus.MATCHED)
                    .ledgerAmount(ledgerAmt)
                    .pspAmount(pspAmt)
                    .bankAmount(bankAmt)
                    .currency(currency)
                    .build());
        }
        return Optional.empty();
    }
}
