package com.recon.classifier.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recon.classifier.persistence.ExceptionRepository;
import com.recon.classifier.rules.ClassificationChain;
import com.recon.classifier.rules.ClassificationResult;
import com.recon.events.exception.ExceptionCandidate;
import com.recon.events.exception.ExceptionCode;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExceptionService {

    private static final Logger log = LoggerFactory.getLogger(ExceptionService.class);

    private final ClassificationChain chain;
    private final ExceptionRepository repo;
    private final ObjectMapper mapper;

    public ExceptionService(ClassificationChain chain, ExceptionRepository repo, ObjectMapper mapper) {
        this.chain = chain;
        this.repo = repo;
        this.mapper = mapper;
    }

    /**
     * Classify + persist in ONE DB transaction. Idempotent on candidate_id.
     * @return the result if a new row was written; empty if this was a redelivery.
     */
    @Transactional
    public Optional<ClassificationResult> process(ExceptionCandidate c) {
        ClassificationResult result = chain.classify(c);

        List<String> sources = c.presentSources().stream().map(Enum::name).sorted().toList();
        String sourcesJson = toJson(sources);
        String rawJson = toJson(c.events());

        boolean inserted = repo.insertIfAbsent(c.candidateId(), c.transactionRef(), result.code().name(),
                sourcesJson, rawJson, result.ruleName(), toJson(result.detail()), c.detectedAt());

        if (!inserted) {
            log.debug("Candidate {} already processed, skipping", c.candidateId());
            return Optional.empty();
        }
        if (result.code() == ExceptionCode.LATE_ARRIVAL) {
            int updated = repo.appendToOpenException(c.transactionRef(), sourcesJson, rawJson);
            log.info("Late arrival for {} appended to {} open exception(s)", c.transactionRef(), updated);
        }
        log.info("Exception {} ref={} rule={} candidate={}", result.code(), c.transactionRef(),
                result.ruleName(), c.candidateId());
        return Optional.of(result);
    }

    private String toJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise to JSON", e);
        }
    }
}
