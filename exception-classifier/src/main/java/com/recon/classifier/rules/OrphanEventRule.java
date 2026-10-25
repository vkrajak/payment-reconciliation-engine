package com.recon.classifier.rules;

import com.recon.classifier.config.ClassifierProperties;
import com.recon.events.exception.CandidateTrigger;
import com.recon.events.exception.ExceptionCandidate;
import com.recon.events.exception.ExceptionCode;
import com.recon.events.exception.SourceType;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Ledger never appeared, even after window + grace. Normally a candidate is first classified as
 * MISSING_SOURCE at window expiry; OrphanSweepJob upgrades it once the grace period passes.
 */
@Component
@Order(50)
public class OrphanEventRule implements ClassificationRule {

    private final ClassifierProperties props;

    public OrphanEventRule(ClassifierProperties props) {
        this.props = props;
    }

    @Override public String name() { return "OrphanEventRule"; }

    @Override
    public Optional<ClassificationResult> classify(ExceptionCandidate c) {
        if (c.trigger() != CandidateTrigger.WINDOW_EXPIRED || c.presentSources().contains(SourceType.LEDGER)) {
            return Optional.empty();
        }
        Duration age = Duration.between(c.firstEventAt(), c.detectedAt());
        Duration threshold = props.window().plus(props.orphanGrace());
        if (age.compareTo(threshold) < 0) {
            return Optional.empty();
        }
        return Optional.of(new ClassificationResult(ExceptionCode.ORPHAN_EVENT, name(),
                Map.of("sourcesSeen", c.presentSources().toString(),
                        "ageSeconds", age.toSeconds(),
                        "thresholdSeconds", threshold.toSeconds())));
    }
}
