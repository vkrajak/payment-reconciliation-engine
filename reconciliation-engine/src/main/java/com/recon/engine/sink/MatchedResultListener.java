package com.recon.engine.sink;

import com.recon.events.ReconciliationResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Deliberately a plain {@code @KafkaListener}, NOT part of the Streams
 * topology itself -- persistence is a separate concern from the join/match
 * logic, which keeps the topology's business rules unit-testable with
 * Kafka Streams' TopologyTestDriver without needing a real (or embedded)
 * Postgres in that test. This class owns the recon-results -> Postgres
 * write, and nothing else.
 * <p>
 * PHASE 4: full idempotency-key design from blueprint sec 4.3. One
 * adaptation worth calling out explicitly: the blueprint's generic pattern
 * dedupes on the source system's {@code event_id}. By the time a message
 * reaches THIS sink, three source events (ledger/psp/bank) have already been
 * merged into one {@link ReconciliationResult} -- there is no single
 * upstream event_id left to dedupe on. So {@code resultId} (a UUID minted by
 * reconciliation-engine once per emitted result, see
 * ReconciliationTopology#toAvroResult) plays that role instead: it
 * identifies "this specific emission of a result", which is precisely what
 * needs deduping if Kafka's at-least-once delivery redelivers the same
 * message (e.g. this listener crashes after committing its Postgres write
 * but before its Kafka offset commit, and reprocesses the same record on
 * restart). The three original source event_ids are still carried through
 * ({@code ledgerEventId}/{@code pspEventId}/{@code bankEventId}) but purely
 * for audit traceability, not for the dedup decision itself.
 */
@Slf4j
@Component
public class MatchedResultListener {

    private static final String MARK_PROCESSED_SQL = """
            INSERT INTO processed_events (event_id, transaction_ref)
            VALUES (?, ?)
            ON CONFLICT (event_id) DO NOTHING
            """;

    private static final String INSERT_MATCHED_SQL = """
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

    /**
     * {@code @Transactional} is what makes this blueprint sec 4.3's actual
     * design, not just two independent statements that happen to run next to
     * each other: the idempotency-key insert and the business write commit
     * or roll back together as one unit. If the second insert were to fail
     * for some unrelated reason (a constraint violation, a connectivity
     * blip), the first insert's dedup marker rolls back with it -- so a
     * genuinely-failed attempt does NOT get permanently marked as processed,
     * and a retry (whether Kafka-driven redelivery or a manual replay) gets
     * a fair second attempt rather than being silently swallowed forever.
     */
    @KafkaListener(topics = "${recon.engine.recon-results-topic}", groupId = "${recon.engine.application-id}-sink")
    @Transactional
    public void onReconciliationResult(ReconciliationResult result) {
        boolean isNewDelivery = tryMarkProcessed(result.getResultId(), result.getTransactionRef());

        if (!isNewDelivery) {
            // THIS is the detection+logging that Phase 3's bare ON CONFLICT on
            // matched_transactions alone could never give us -- that only prevented a
            // crash; this tells us definitively that a redelivery happened and we
            // correctly no-op'd it, which is exactly the auditable signal blueprint
            // sec 4.3 calls for.
            log.info("DUPLICATE DETECTED: resultId={} txn_ref={} already processed -- " +
                            "redelivery correctly no-op'd, business write skipped",
                    result.getResultId(), result.getTransactionRef());
            return;
        }

        persistMatchedTransaction(result);
    }

    /**
     * @return true if this resultId had never been seen before (a genuinely new
     *         delivery, proceed to the business write), false if it was already
     *         marked processed (a redelivery, skip the business write).
     *         <p>
     *         Uses ON CONFLICT DO NOTHING + checking the affected-row count,
     *         rather than attempting the insert and catching a
     *         DuplicateKeyException, deliberately: Postgres marks a transaction
     *         "aborted" the moment any statement inside it errors, and every
     *         subsequent statement in that same transaction then fails too until
     *         a rollback -- which would make it unsafe to run the matched_transactions
     *         insert afterward even in the "this was actually a duplicate, skip
     *         gracefully" case. Checking the row count sidesteps that failure
     *         mode entirely: no statement ever errors, so the transaction stays
     *         healthy regardless of which branch we take next.
     */
    private boolean tryMarkProcessed(String resultId, String transactionRef) {
        int rows = jdbcTemplate.update(MARK_PROCESSED_SQL, UUID.fromString(resultId), transactionRef);
        return rows == 1;
    }

    private void persistMatchedTransaction(ReconciliationResult result) {
        LocalDate businessDate = result.getMatchedAt().atZone(ZoneOffset.UTC).toLocalDate();

        int rows = jdbcTemplate.update(INSERT_MATCHED_SQL,
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
            // Should be rare now that processed_events already caught true redeliveries
            // above -- this remaining safety net only fires for a genuinely distinct
            // resultId that still collides on (transaction_ref, match_version,
            // business_date), which Phase 5's late-arrival re-evaluation could
            // legitimately produce. Worth knowing about if it shows up in logs, not
            // itself a bug.
            log.warn("txn_ref={} match_version={} business_date={} resultId={} had a NEW resultId " +
                            "but still collided on matched_transactions' own unique constraint -- investigate",
                    result.getTransactionRef(), result.getMatchVersion(), businessDate, result.getResultId());
        } else {
            log.info("Persisted {} txn_ref={} amount={} {} settlement_lag_ms={} resultId={}",
                    result.getMatchStatus(), result.getTransactionRef(), result.getLedgerAmount(),
                    result.getCurrency(), result.getSettlementLagMs(), result.getResultId());
        }
    }
}
