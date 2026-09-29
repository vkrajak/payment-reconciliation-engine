package com.recon.sim.bank.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One row that will eventually become one line in a statement CSV. Held in
 * {@link com.recon.sim.bank.writer.PendingLineBuffer} until {@code readyAtEpochMillis}
 * has passed, which is how ParticipantBehavior.EMIT_DELAYED is implemented --
 * the line simply isn't eligible for the next file flush until its delay elapses.
 *
 * @param statementLineId the bank's own line identifier -- generated here, written into
 *                         the CSV, and later used by StatementFileWatcher to derive a
 *                         deterministic event_id (see BankEvent.avsc doc on idempotent re-ingestion)
 */
public record PendingBankLine(
        String statementLineId,
        String transactionRef,
        String accountId,
        BigDecimal amount,
        String currency,
        String txnType,
        LocalDate valueDate,
        long readyAtEpochMillis
) {
}
