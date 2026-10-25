package com.recon.classifier.rules;

import com.recon.events.exception.CandidateTrigger;
import com.recon.events.exception.ExceptionCandidate;
import com.recon.events.exception.ExceptionCode;
import com.recon.events.exception.SourceSnapshot;
import com.recon.events.exception.SourceType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Same txn_ref + same source but different event_id = two genuine transactions claiming one ref. */
@Component
@Order(20)
public class DuplicateTransactionRefRule implements ClassificationRule {
    @Override public String name() { return "DuplicateTransactionRefRule"; }

    @Override
    public Optional<ClassificationResult> classify(ExceptionCandidate c) {
        Map<String, Object> conflicts = new LinkedHashMap<>();
        for (Map.Entry<SourceType, List<SourceSnapshot>> e : c.bySource().entrySet()) {
            List<String> ids = e.getValue().stream().map(SourceSnapshot::eventId).distinct().toList();
            if (ids.size() > 1) {
                conflicts.put(e.getKey().name(), ids);
            }
        }
        if (conflicts.isEmpty() && c.trigger() != CandidateTrigger.DUPLICATE_REF) {
            return Optional.empty();
        }
        return Optional.of(new ClassificationResult(ExceptionCode.DUPLICATE_TRANSACTION_REF, name(),
                Map.of("conflictingEventIds", conflicts)));
    }
}
