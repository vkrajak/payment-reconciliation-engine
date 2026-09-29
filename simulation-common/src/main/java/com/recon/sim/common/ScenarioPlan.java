package com.recon.sim.common;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;

/**
 * The "plan" for one simulated business transaction, decided entirely by
 * ledger-simulator and published (as plain JSON, not Avro -- this never
 * reaches production topics) to the internal {@code sim-scenarios} topic.
 * psp-simulator and bank-file-ingester each consume it and independently
 * decide how -- and whether -- to emit their own event for this
 * {@code transactionRef}, based on {@link #pspBehavior()} / {@link #bankBehavior()}.
 * <p>
 * Why a coordinator at all, instead of three fully independent simulators?
 * Because a MISSING_SOURCE or AMOUNT_MISMATCH exception is only interesting
 * if it's the ONLY thing wrong with that transaction -- three simulators
 * picking random behavior independently would mostly produce noise. Centralizing
 * the decision in one place makes the exception distribution match blueprint
 * sec 6 on purpose, which is what makes the downstream dashboard metrics meaningful.
 */
@Value
@Builder
public class ScenarioPlan {

    String transactionRef;
    String accountId;
    BigDecimal amount;          // the "true" amount; PSP/bank copy this unless behavior says otherwise
    String currency;
    String txnType;             // "DEBIT" or "CREDIT", mirrors LedgerEvent.TxnType
    long ledgerPostedAtEpochMillis;

    /** If false, ledger-simulator itself will not publish a LedgerEvent for this ref
     *  (models an ORPHAN_EVENT: a transaction the ledger never recorded but PSP/bank saw). */
    boolean ledgerEmits;

    ParticipantBehavior pspBehavior;
    BigDecimal pspAmountOverride;   // used only when pspBehavior == EMIT_MISMATCHED_AMOUNT
    long pspDelayMs;                // used only when pspBehavior == EMIT_DELAYED

    ParticipantBehavior bankBehavior;
    BigDecimal bankAmountOverride;  // used only when bankBehavior == EMIT_MISMATCHED_AMOUNT
    long bankDelayMs;               // used only when bankBehavior == EMIT_DELAYED
    int bankValueDateOffsetDays;    // bank statements report a date, not a timestamp

    @JsonCreator
    public ScenarioPlan(
            @JsonProperty("transactionRef") String transactionRef,
            @JsonProperty("accountId") String accountId,
            @JsonProperty("amount") BigDecimal amount,
            @JsonProperty("currency") String currency,
            @JsonProperty("txnType") String txnType,
            @JsonProperty("ledgerPostedAtEpochMillis") long ledgerPostedAtEpochMillis,
            @JsonProperty("ledgerEmits") boolean ledgerEmits,
            @JsonProperty("pspBehavior") ParticipantBehavior pspBehavior,
            @JsonProperty("pspAmountOverride") BigDecimal pspAmountOverride,
            @JsonProperty("pspDelayMs") long pspDelayMs,
            @JsonProperty("bankBehavior") ParticipantBehavior bankBehavior,
            @JsonProperty("bankAmountOverride") BigDecimal bankAmountOverride,
            @JsonProperty("bankDelayMs") long bankDelayMs,
            @JsonProperty("bankValueDateOffsetDays") int bankValueDateOffsetDays) {
        this.transactionRef = transactionRef;
        this.accountId = accountId;
        this.amount = amount;
        this.currency = currency;
        this.txnType = txnType;
        this.ledgerPostedAtEpochMillis = ledgerPostedAtEpochMillis;
        this.ledgerEmits = ledgerEmits;
        this.pspBehavior = pspBehavior;
        this.pspAmountOverride = pspAmountOverride;
        this.pspDelayMs = pspDelayMs;
        this.bankBehavior = bankBehavior;
        this.bankAmountOverride = bankAmountOverride;
        this.bankDelayMs = bankDelayMs;
        this.bankValueDateOffsetDays = bankValueDateOffsetDays;
    }
}
