package com.recon.classifier.rules;

import com.recon.events.exception.CandidateTrigger;
import com.recon.events.exception.ExceptionCandidate;
import com.recon.events.exception.ExceptionCode;
import java.util.Map;
import java.util.Optional;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(10)
public class LateArrivalRule implements ClassificationRule {
    @Override public String name() { return "LateArrivalRule"; }

    @Override
    public Optional<ClassificationResult> classify(ExceptionCandidate c) {
        if (c.trigger() != CandidateTrigger.POST_RESOLUTION_EVENT) {
            return Optional.empty();
        }
        return Optional.of(new ClassificationResult(ExceptionCode.LATE_ARRIVAL, name(),
                Map.of("priorResolution", String.valueOf(c.priorResolution()))));
    }
}
