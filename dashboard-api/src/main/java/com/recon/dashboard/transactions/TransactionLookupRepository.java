package com.recon.dashboard.transactions;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TransactionLookupRepository {

    public record MatchedRow(String matchStatus, Instant matchedAt, boolean hasLedger, boolean hasPsp, boolean hasBank) { }

    public record ExceptionRow(String code, String status, Instant detectedAt, Instant updatedAt, List<String> sources) { }

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public TransactionLookupRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public Optional<MatchedRow> latestMatch(String transactionRef) {
        return jdbc.query("""
                SELECT match_status, matched_at,
                       ledger_amount IS NOT NULL AS has_ledger,
                       psp_amount    IS NOT NULL AS has_psp,
                       bank_amount   IS NOT NULL AS has_bank
                  FROM matched_transactions
                 WHERE transaction_ref = ?
                 ORDER BY match_version DESC
                 LIMIT 1
                """, (rs, i) -> new MatchedRow(rs.getString("match_status"),
                rs.getObject("matched_at", OffsetDateTime.class).toInstant(),
                rs.getBoolean("has_ledger"), rs.getBoolean("has_psp"), rs.getBoolean("has_bank")),
                transactionRef).stream().findFirst();
    }

    /** LATE_ARRIVAL rows are bookkeeping, not a status of their own, so they are skipped. */
    public Optional<ExceptionRow> latestException(String transactionRef) {
        return jdbc.query("""
                SELECT exception_code, status, first_detected_at, last_updated_at, sources_present::text AS sources
                  FROM exceptions
                 WHERE transaction_ref = ? AND exception_code <> 'LATE_ARRIVAL'
                 ORDER BY first_detected_at DESC
                 LIMIT 1
                """, (rs, i) -> new ExceptionRow(rs.getString("exception_code"), rs.getString("status"),
                rs.getObject("first_detected_at", OffsetDateTime.class).toInstant(),
                rs.getObject("last_updated_at", OffsetDateTime.class).toInstant(),
                parseSources(rs.getString("sources"))), transactionRef).stream().findFirst();
    }

    private List<String> parseSources(String json) {
        try {
            return json == null ? List.of() : mapper.readValue(json, new TypeReference<List<String>>() { });
        } catch (Exception e) {
            return List.of();
        }
    }
}
