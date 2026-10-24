package com.recon.dashboard.exceptions;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Read-mostly view of the Phase 5 exceptions table. Writes happen only via ExceptionJpaRepository.markResolved. */
@Entity
@Table(name = "exceptions")
public class ExceptionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "candidate_id")
    private String candidateId;

    @Column(name = "transaction_ref")
    private String transactionRef;

    @Column(name = "exception_code")
    private String exceptionCode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "sources_present", columnDefinition = "jsonb")
    private JsonNode sourcesPresent;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_events", columnDefinition = "jsonb")
    private JsonNode rawEvents;

    @Column(name = "rule_name")
    private String ruleName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "classification_detail", columnDefinition = "jsonb")
    private JsonNode classificationDetail;

    @Column(name = "status")
    private String status;

    @Column(name = "first_detected_at")
    private Instant firstDetectedAt;

    @Column(name = "last_updated_at")
    private Instant lastUpdatedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolved_by")
    private String resolvedBy;

    @Column(name = "resolution_note")
    private String resolutionNote;

    protected ExceptionEntity() { }

    public Long getId() { return id; }
    public String getCandidateId() { return candidateId; }
    public String getTransactionRef() { return transactionRef; }
    public String getExceptionCode() { return exceptionCode; }
    public JsonNode getSourcesPresent() { return sourcesPresent; }
    public JsonNode getRawEvents() { return rawEvents; }
    public String getRuleName() { return ruleName; }
    public JsonNode getClassificationDetail() { return classificationDetail; }
    public String getStatus() { return status; }
    public Instant getFirstDetectedAt() { return firstDetectedAt; }
    public Instant getLastUpdatedAt() { return lastUpdatedAt; }
    public Instant getResolvedAt() { return resolvedAt; }
    public String getResolvedBy() { return resolvedBy; }
    public String getResolutionNote() { return resolutionNote; }
}
