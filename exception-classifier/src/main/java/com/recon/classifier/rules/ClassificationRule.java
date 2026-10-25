package com.recon.classifier.rules;

import com.recon.events.exception.ExceptionCandidate;
import java.util.Optional;

/** Strategy: one rule per exception code. First rule (by @Order) to return a result wins. */
public interface ClassificationRule {
    String name();

    Optional<ClassificationResult> classify(ExceptionCandidate candidate);
}
