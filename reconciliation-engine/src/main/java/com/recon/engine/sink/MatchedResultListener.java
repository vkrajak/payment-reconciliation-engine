package com.recon.engine.sink;

import com.recon.events.ReconciliationResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Deliberately a plain {@code @KafkaListener}, NOT part of the Streams
 * topology itself -- persistence is a separate concern from the join/match
 * logic, which keeps the topology's business rules unit-testable with
 * Kafka Streams' TopologyTestDriver without needing a real (or embedded)
 * Postgres in that test. This class owns the recon-results -> Postgres
 * write, and nothing else.
 * <p>
 * PHASE 3 SCOPE NOTE: this does NOT yet check processed_events for
 * idempotency (Phase 4) -- it relies solely on the ON CONFLICT ... DO
 * NOTHING clause below as a basic safety net against crashing on a
 * redelivered message. That is not the same guarantee as the full
 * idempotency-key design in blueprint sec 4.3; it only prevents a duplicate
 * (transactionRef, matchVersion, businessDate) tuple from erroring, it does
 * not detect or log that a duplicate was skipped. Phase 4 replaces this.
 */
@Slf4j
@Component
public class MatchedResultListener {

    private static final String INSERT_SQL = """
            INSERT INTO matched_transactions
                (transaction_ref, match_version, match_status, ledger_amount, psp_amount, bank_amount,
                 currency, settlement_lag_ms, matched_at, business_date)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (transaction_ref, match_version, business_date) DO NOTHING
            """;

    private final JdbcTemplate jdbcTemplate;

    public MatchedResultListener(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @KafkaListener(topics = "${recon.engine.recon-results-topic}", groupId = "${recon.engine.application-id}-sink")
    public void onReconciliationResult(ReconciliationResult result) {
        LocalDate businessDate = result.getMatchedAt().atZone(ZoneOffset.UTC).toLocalDate();

        int rows = jdbcTemplate.update(INSERT_SQL,
                result.getTransactionRef(),
                result.getMatchVersion(),
                result.getMatchStatus().name(),
                result.getLedgerAmount(),
                result.getPspAmount(),
                result.getBankAmount(),
                result.getCurrency(),
                result.getSettlementLagMs(),
                Timestamp.from(result.getMatchedAt()),
                businessDate);

        if (rows == 0) {
            log.debug("txn_ref={} match_version={} business_date={} already present, ON CONFLICT no-op",
                    result.getTransactionRef(), result.getMatchVersion(), businessDate);
        } else {
            log.info("Persisted {} txn_ref={} amount={} {} settlement_lag_ms={}",
                    result.getMatchStatus(), result.getTransactionRef(), result.getLedgerAmount(),
                    result.getCurrency(), result.getSettlementLagMs());
        }
    }
}
