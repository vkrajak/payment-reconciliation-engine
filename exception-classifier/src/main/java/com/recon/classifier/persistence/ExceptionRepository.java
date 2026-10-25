package com.recon.classifier.persistence;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ExceptionRepository {

    private final JdbcTemplate jdbc;

    public ExceptionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return true if a new row was written, false if candidate_id already existed (redelivery). */
    public boolean insertIfAbsent(String candidateId, String txnRef, String code, String sourcesJson,
                                  String rawEventsJson, String ruleName, String detailJson, Instant detectedAt) {
        int rows = jdbc.update("""
                INSERT INTO exceptions
                    (candidate_id, transaction_ref, exception_code, sources_present, raw_events,
                     rule_name, classification_detail, first_detected_at)
                VALUES (?, ?, ?, ?::jsonb, ?::jsonb, ?, ?::jsonb, ?)
                ON CONFLICT (candidate_id) DO NOTHING
                """,
                candidateId, txnRef, code, sourcesJson, rawEventsJson, ruleName, detailJson,
                OffsetDateTime.ofInstant(detectedAt, ZoneOffset.UTC));
        return rows == 1;
    }

    /** Late arrival: fold the new event into the most recent non-resolved exception for this ref. */
    public int appendToOpenException(String txnRef, String sourcesJson, String rawEventsJson) {
        return jdbc.update("""
                UPDATE exceptions
                   SET raw_events      = raw_events || ?::jsonb,
                       sources_present = (SELECT jsonb_agg(DISTINCT s)
                                            FROM jsonb_array_elements(sources_present || ?::jsonb) AS s),
                       last_updated_at = now()
                 WHERE id = (SELECT id FROM exceptions
                              WHERE transaction_ref = ?
                                AND status <> 'RESOLVED'
                                AND exception_code <> 'LATE_ARRIVAL'
                              ORDER BY first_detected_at DESC
                              LIMIT 1)
                """, rawEventsJson, sourcesJson, txnRef);
    }

    /**
     * MISSING_SOURCE with no LEDGER, older than grace -> ORPHAN_EVENT. Idempotent; safe on many pods.
     * @return transaction_refs that were upgraded (Phase 6: used to refresh Redis).
     */
    public List<String> upgradeMissingToOrphan(Duration grace) {
        return jdbc.queryForList("""
                UPDATE exceptions
                   SET exception_code = 'ORPHAN_EVENT',
                       rule_name = 'OrphanSweepJob',
                       classification_detail = COALESCE(classification_detail, '{}'::jsonb)
                           || jsonb_build_object('upgradedFrom', 'MISSING_SOURCE', 'upgradedAt', now()),
                       last_updated_at = now()
                 WHERE status = 'OPEN'
                   AND exception_code = 'MISSING_SOURCE'
                   AND NOT (sources_present @> '["LEDGER"]'::jsonb)
                   AND first_detected_at < now() - (?::double precision * interval '1 second')
                RETURNING transaction_ref
                """, String.class, (double) grace.toSeconds());
    }
}
