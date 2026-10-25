package com.recon.classifier.rules;

import com.recon.events.exception.ExceptionCandidate;
import com.recon.events.exception.ExceptionCode;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ClassificationChain {

    private final List<ClassificationRule> rules; // Spring injects in @Order

    public ClassificationChain(List<ClassificationRule> rules) {
        this.rules = rules;
    }

    public ClassificationResult classify(ExceptionCandidate candidate) {
        for (ClassificationRule rule : rules) {
            var result = rule.classify(candidate);
            if (result.isPresent()) {
                return result.get();
            }
        }
        return new ClassificationResult(ExceptionCode.UNCLASSIFIED, "Fallback",
                Map.of("trigger", String.valueOf(candidate.trigger())));
    }
}
