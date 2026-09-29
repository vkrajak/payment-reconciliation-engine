package com.recon.sim.ledger.generator;

import com.recon.sim.common.ParticipantBehavior;
import com.recon.sim.common.ScenarioPlan;
import com.recon.sim.ledger.config.SimulatorProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;

/**
 * Picks one of seven scenario "shapes" per transaction, weighted by
 * {@code sim.ledger.weights.*} in application.yml, and builds the full
 * {@link ScenarioPlan} for it. This is the single place that decides what
 * mix of MATCHED / AMOUNT_MISMATCH / MISSING_SOURCE / DUPLICATE_TRANSACTION_REF /
 * SETTLEMENT_LAG_WARNING outcomes the whole downstream system will see --
 * tune the weights here to change the demo's exception mix without touching
 * any other module.
 */
@Component
public class ScenarioFactory {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SimulatorProperties properties;

    public ScenarioFactory(SimulatorProperties properties) {
        this.properties = properties;
    }

    public ScenarioPlan next() {
        String txnRef = RandomDataPools.randomTransactionRef();
        String accountId = RandomDataPools.randomAccountId();
        BigDecimal amount = RandomDataPools.randomAmount();
        String currency = RandomDataPools.randomCurrency();
        String txnType = RandomDataPools.randomTxnType();
        long postedAt = Instant.now().toEpochMilli();

        ScenarioKind kind = pickWeighted();

        ScenarioPlan.ScenarioPlanBuilder builder = ScenarioPlan.builder()
                .transactionRef(txnRef)
                .accountId(accountId)
                .amount(amount)
                .currency(currency)
                .txnType(txnType)
                .ledgerPostedAtEpochMillis(postedAt)
                .ledgerEmits(true)
                .pspBehavior(ParticipantBehavior.EMIT_MATCHING)
                .pspDelayMs(0)
                .bankBehavior(ParticipantBehavior.EMIT_MATCHING)
                .bankDelayMs(0)
                .bankValueDateOffsetDays(0);

        switch (kind) {
            case HAPPY_PATH -> {
                // defaults above already are the happy path; nothing to change
            }
            case AMOUNT_MISMATCH -> {
                // small deterministic-looking drift so it's obviously "wrong", never zero
                BigDecimal drift = BigDecimal.valueOf(1 + RANDOM.nextInt(50)).setScale(2);
                builder.pspBehavior(ParticipantBehavior.EMIT_MISMATCHED_AMOUNT)
                       .pspAmountOverride(amount.add(drift));
            }
            case MISSING_PSP -> builder.pspBehavior(ParticipantBehavior.SKIP);
            case MISSING_BANK -> builder.bankBehavior(ParticipantBehavior.SKIP);
            case DUPLICATE_PSP -> builder.pspBehavior(ParticipantBehavior.EMIT_DUPLICATE);
            case DUPLICATE_BANK -> builder.bankBehavior(ParticipantBehavior.EMIT_DUPLICATE);
            case DELAYED_BANK -> builder.bankBehavior(ParticipantBehavior.EMIT_DELAYED)
                                         .bankDelayMs(45_000); // beyond a short dev-tuned match window on purpose
        }

        return builder.build();
    }

    private ScenarioKind pickWeighted() {
        var w = properties.getWeights();
        int total = w.getHappyPath() + w.getAmountMismatch() + w.getMissingPsp() + w.getMissingBank()
                + w.getDuplicatePsp() + w.getDuplicateBank() + w.getDelayedBank();
        int roll = RANDOM.nextInt(Math.max(total, 1));

        int cursor = w.getHappyPath();
        if (roll < cursor) return ScenarioKind.HAPPY_PATH;
        cursor += w.getAmountMismatch();
        if (roll < cursor) return ScenarioKind.AMOUNT_MISMATCH;
        cursor += w.getMissingPsp();
        if (roll < cursor) return ScenarioKind.MISSING_PSP;
        cursor += w.getMissingBank();
        if (roll < cursor) return ScenarioKind.MISSING_BANK;
        cursor += w.getDuplicatePsp();
        if (roll < cursor) return ScenarioKind.DUPLICATE_PSP;
        cursor += w.getDuplicateBank();
        if (roll < cursor) return ScenarioKind.DUPLICATE_BANK;
        return ScenarioKind.DELAYED_BANK;
    }

    private enum ScenarioKind {
        HAPPY_PATH, AMOUNT_MISMATCH, MISSING_PSP, MISSING_BANK,
        DUPLICATE_PSP, DUPLICATE_BANK, DELAYED_BANK
    }
}
