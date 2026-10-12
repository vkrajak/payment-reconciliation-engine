package com.recon.engine.model;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/** What ExceptionClassifier returns -- kept Avro-free, same rationale as MatchOutcome. */
@Value
@Builder
public class ExceptionOutcome {
    ExceptionCode exceptionCode;
    List<String> sourcesPresent;
    String rawLedgerEvent;  // JSON snapshot of SourceEventEnvelope, null if that source never arrived
    String rawPspEvent;
    String rawBankEvent;

    public enum ExceptionCode {
        MISSING_SOURCE, AMOUNT_MISMATCH, CURRENCY_MISMATCH,
        DUPLICATE_TRANSACTION_REF, ORPHAN_EVENT, LATE_ARRIVAL
    }
}
