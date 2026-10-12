package com.recon.classifier.sink;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recon.events.ExceptionEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Deliberately a plain {@code @KafkaListener}, same rationale as
 * reconciliation-engine's MatchedResultListener: persistence is a separate
 * concern kept out of the (stateful, harder-to-test) Streams topology.
 * <p>
 * Idempotency: identical pattern to MatchedResultListener (Phase 4) -- ON
 * CONFLICT DO NOTHING + row-count check against the SAME processed_events
 * table, keyed on ExceptionEvent.resultId. One table serving two sinks is
 * deliberate: a processed_events row is just "this UUID was handled",
 * agnostic to whether the UUID came from a ReconciliationResult or an
 * ExceptionEvent -- there's no reason to duplicate the table.
 */
@Slf4j
@Component
public class ExceptionPersistenceListener {

    private static final String MARK_PROCESSED_SQL = """
            INSERT INTO processed_events (event_id, transaction_ref)
            VALUES (?, ?)
            ON CONFLICT (event_id) DO NOTHING
            """;

    private static final String INSERT_EXCEPTION_SQL = """
            INSERT INTO exceptions
                (transaction_ref, exception_code, sources_present, raw_events, status, first_detected_at)
            VALUES (?, ?, ?::jsonb, ?::jsonb, 'OPEN', ?)
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ExceptionPersistenceListener(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "${recon.classifier.exception-events-topic}", groupId = "${recon.classifier.consumer-group-id}")
    @Transactional
    public void onExceptionEvent(ExceptionEvent event) {
        boolean isNewDelivery = tryMarkProcessed(event.getResultId(), event.getTransactionRef());

        if (!isNewDelivery) {
            log.info("DUPLICATE DETECTED: resultId={} txn_ref={} already processed -- redelivery no-op'd",
                    event.getResultId(), event.getTransactionRef());
            return;
        }

        persistException(event);
    }

    private boolean tryMarkProcessed(String resultId, String transactionRef) {
        int rows = jdbcTemplate.update(MARK_PROCESSED_SQL, UUID.fromString(resultId), transactionRef);
        return rows == 1;
    }

    private void persistException(ExceptionEvent event) {
        try {
            String sourcesPresentJson = objectMapper.writeValueAsString(event.getSourcesPresent());
            String rawEventsJson = buildRawEventsJson(event);

            jdbcTemplate.update(INSERT_EXCEPTION_SQL,
                    event.getTransactionRef(),
                    event.getExceptionCode().name(),
                    sourcesPresentJson,
                    rawEventsJson,
                    Timestamp.from(event.getDetectedAt()));

            log.info("Persisted exception {} txn_ref={} sources_present={} resultId={}",
                    event.getExceptionCode(), event.getTransactionRef(), event.getSourcesPresent(), event.getResultId());
        } catch (Exception e) {
            // Rethrow so @Transactional rolls back the processed_events marker too --
            // a JSON build failure here should look like "this delivery failed" to the
            // idempotency layer, not "silently succeeded with no exception row written".
            throw new RuntimeException("Failed to persist exception for txn_ref=" + event.getTransactionRef(), e);
        }
    }

    /** Combines the three raw per-source JSON snapshots (each already a JSON
     *  string or null, see ExceptionClassifier#toJson) into one JSON object,
     *  parsing each before re-embedding so they land as nested JSON structure
     *  rather than a doubly-escaped string inside the column. */
    private String buildRawEventsJson(ExceptionEvent event) throws Exception {
        Map<String, JsonNode> combined = new LinkedHashMap<>();
        combined.put("ledger", parseOrNull(event.getRawLedgerEvent()));
        combined.put("psp", parseOrNull(event.getRawPspEvent()));
        combined.put("bank", parseOrNull(event.getRawBankEvent()));
        return objectMapper.writeValueAsString(combined);
    }

    private JsonNode parseOrNull(String raw) throws Exception {
        return raw == null ? null : objectMapper.readTree(raw);
    }
}
