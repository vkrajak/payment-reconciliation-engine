package com.recon.events.exception;

public enum ExceptionCode {
    MISSING_SOURCE,
    AMOUNT_MISMATCH,
    CURRENCY_MISMATCH,
    DUPLICATE_TRANSACTION_REF,
    ORPHAN_EVENT,
    LATE_ARRIVAL,
    /** Safety net: nothing matched. Never dropped; alerts engineering. */
    UNCLASSIFIED
}
