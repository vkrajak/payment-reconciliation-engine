package com.recon.classifier.rules;

import com.recon.events.exception.ExceptionCandidate;
import com.recon.events.exception.ExceptionCode;
import com.recon.events.exception.SourceType;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(60)
public class MissingSourceRule implements ClassificationRule {
    @Override public String name() { return "MissingSourceRule"; }

    @Override
    public Optional<ClassificationResult> classify(ExceptionCandidate c) {
        if (c.presentSources().size() >= SourceType.values().length) {
            return Optional.empty();
        }
        List<String> missing = Arrays.stream(SourceType.values())
                .filter(s -> !c.presentSources().contains(s)).map(Enum::name).toList();
        return Optional.of(new ClassificationResult(ExceptionCode.MISSING_SOURCE, name(),
                Map.of("missing", missing)));
    }
}
