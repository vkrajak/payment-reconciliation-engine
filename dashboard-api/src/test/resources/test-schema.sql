-- Test-only schema. exceptions mirrors V5; matched_transactions is the unpartitioned equivalent of the blueprint table.
CREATE TABLE exceptions (
    id                    BIGSERIAL PRIMARY KEY,
    candidate_id          VARCHAR(64)  NOT NULL,
    transaction_ref       VARCHAR(64)  NOT NULL,
    exception_code        VARCHAR(40)  NOT NULL,
    sources_present       JSONB        NOT NULL,
    raw_events            JSONB        NOT NULL,
    rule_name             VARCHAR(60)  NOT NULL,
    classification_detail JSONB,
    status                VARCHAR(20)  NOT NULL DEFAULT 'OPEN',
    first_detected_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    resolved_at           TIMESTAMPTZ,
    resolved_by           VARCHAR(100),
    resolution_note       TEXT,
    CONSTRAINT uq_exceptions_candidate UNIQUE (candidate_id)
);

CREATE TABLE matched_transactions (
    id                BIGSERIAL PRIMARY KEY,
    transaction_ref   VARCHAR(64) NOT NULL,
    match_version     INT NOT NULL DEFAULT 1,
    match_status      VARCHAR(30) NOT NULL,
    ledger_amount     NUMERIC(18,2),
    psp_amount        NUMERIC(18,2),
    bank_amount       NUMERIC(18,2),
    currency          CHAR(3) NOT NULL,
    settlement_lag_ms BIGINT,
    matched_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    business_date     DATE NOT NULL,
    UNIQUE (transaction_ref, match_version)
);
