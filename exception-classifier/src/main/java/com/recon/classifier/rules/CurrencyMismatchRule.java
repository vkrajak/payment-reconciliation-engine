package com.recon.classifier.rules;

import com.recon.events.exception.ExceptionCandidate;
import com.recon.events.exception.ExceptionCode;
import com.recon.events.exception.SourceSnapshot;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Runs before the amount check: comparing amounts across currencies is meaningless. */
@Component
@Order(30)
public class CurrencyMismatchRule implements ClassificationRule {
    @Override public String name() { return "CurrencyMismatchRule"; }

    @Override
    public Optional<ClassificationResult> classify(ExceptionCandidate c) {
        Map<String, String> bySource = new LinkedHashMap<>();
        for (SourceSnapshot e : c.events()) {
            if (e.currency() != null) {
                bySource.put(e.source().name(), e.currency());
            }
        }
        if (bySource.values().stream().distinct().count() <= 1) {
            return Optional.empty();
        }
        return Optional.of(new ClassificationResult(ExceptionCode.CURRENCY_MISMATCH, name(),
                Map.of("currencyBySource", bySource)));
    }
}
