package com.recon.classifier.consumer;

import com.recon.classifier.cache.TxnStatusWriter;
import com.recon.classifier.service.ExceptionService;
import com.recon.classifier.service.MalformedCandidateException;
import com.recon.events.exception.ExceptionCandidate;
import com.recon.events.exception.ExceptionCode;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class ExceptionCandidateListener {

    private static final Logger log = LoggerFactory.getLogger(ExceptionCandidateListener.class);

    private final ExceptionService service;
    private final MeterRegistry meters;
    private final TxnStatusWriter statusWriter;   // Phase 6

    public ExceptionCandidateListener(ExceptionService service, MeterRegistry meters, TxnStatusWriter statusWriter) {
        this.service = service;
        this.meters = meters;
        this.statusWriter = statusWriter;
    }

    @KafkaListener(topics = "${recon.kafka.exception-topic:exception-events}",
            groupId = "${spring.kafka.consumer.group-id:exception-classifier}")
    public void onCandidate(ConsumerRecord<String, ExceptionCandidate> record) {
        ExceptionCandidate c = record.value();
        validate(c);
        if (record.key() != null && !record.key().equals(c.transactionRef())) {
            log.warn("Key/transactionRef mismatch key={} ref={}", record.key(), c.transactionRef());
        }
        // process() has committed to Postgres by the time it returns; Redis is updated second (write-through).
        service.process(c).ifPresent(r -> {
            meters.counter("recon.exceptions", "exception_code", r.code().name()).increment();
            if (r.code() != ExceptionCode.LATE_ARRIVAL) {   // a late event must not overwrite the txn's status
                statusWriter.recordException(c.transactionRef(), r.code().name(), c.presentSources(), c.detectedAt());
            }
        });
    }

    private static void validate(ExceptionCandidate c) {
        if (c == null) {
            throw new MalformedCandidateException("null candidate (tombstone?)");
        }
        if (isBlank(c.candidateId()) || isBlank(c.transactionRef()) || c.trigger() == null
                || c.detectedAt() == null || c.events() == null || c.events().isEmpty()) {
            throw new MalformedCandidateException("missing required field(s) on candidate " + c.candidateId());
        }
        boolean badEvent = c.events().stream().anyMatch(e -> e == null || e.source() == null || isBlank(e.eventId()));
        if (badEvent) {
            throw new MalformedCandidateException("event without source/eventId on candidate " + c.candidateId());
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
