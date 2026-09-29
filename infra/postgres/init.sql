-- Runs automatically on first container start (mounted into
-- /docker-entrypoint-initdb.d/). See blueprint sec 7.1 for design rationale.

CREATE TABLE IF NOT EXISTS matched_transactions (
    id                  BIGSERIAL,
    transaction_ref     VARCHAR(64) NOT NULL,
    match_version       INT NOT NULL DEFAULT 1,
    match_status        VARCHAR(30) NOT NULL,
    ledger_amount       NUMERIC(18,2),
    psp_amount          NUMERIC(18,2),
    bank_amount         NUMERIC(18,2),
    currency            CHAR(3) NOT NULL,
    settlement_lag_ms   BIGINT,
    matched_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    business_date       DATE NOT NULL,
    PRIMARY KEY (id, business_date),
    UNIQUE (transaction_ref, match_version, business_date)
) PARTITION BY RANGE (business_date);

-- Default catch-all partition so inserts never fail before the daily-partition
-- job (Phase 5+) is wired up. In production this stays empty; everything
-- lands in a dated partition instead.
CREATE TABLE IF NOT EXISTS matched_transactions_default
    PARTITION OF matched_transactions DEFAULT;

-- A few real dated partitions to develop against immediately.
CREATE TABLE IF NOT EXISTS matched_transactions_2026_09_15
    PARTITION OF matched_transactions FOR VALUES FROM ('2026-09-15') TO ('2026-09-16');
CREATE TABLE IF NOT EXISTS matched_transactions_2026_09_16
    PARTITION OF matched_transactions FOR VALUES FROM ('2026-09-16') TO ('2026-09-17');
CREATE TABLE IF NOT EXISTS matched_transactions_2026_09_17
    PARTITION OF matched_transactions FOR VALUES FROM ('2026-09-17') TO ('2026-09-18');

CREATE INDEX IF NOT EXISTS idx_matched_txn_ref ON matched_transactions (transaction_ref);
CREATE INDEX IF NOT EXISTS idx_matched_business_date ON matched_transactions (business_date);

-- Idempotency ledger: one row per event_id ever successfully processed.
-- The reconciliation-engine checks-and-inserts here in the SAME db
-- transaction as the business write (matched_transactions / exceptions),
-- so a redelivered event_id is a safe no-op. See blueprint sec 4.3.
CREATE TABLE IF NOT EXISTS processed_events (
    event_id        UUID PRIMARY KEY,
    transaction_ref VARCHAR(64) NOT NULL,
    processed_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_processed_events_txn_ref ON processed_events (transaction_ref);

-- Business exceptions (blueprint sec 6). NOT technical failures -- those
-- go to Kafka .DLT topics, never here.
CREATE TABLE IF NOT EXISTS exceptions (
    id                  BIGSERIAL PRIMARY KEY,
    transaction_ref     VARCHAR(64) NOT NULL,
    exception_code      VARCHAR(40) NOT NULL,
    sources_present     JSONB,
    raw_events          JSONB,
    status              VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    first_detected_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at         TIMESTAMPTZ,
    resolved_by         VARCHAR(100),
    resolution_note     TEXT
);
CREATE INDEX IF NOT EXISTS idx_exceptions_status ON exceptions (status);
CREATE INDEX IF NOT EXISTS idx_exceptions_code ON exceptions (exception_code);
CREATE INDEX IF NOT EXISTS idx_exceptions_txn_ref ON exceptions (transaction_ref);
