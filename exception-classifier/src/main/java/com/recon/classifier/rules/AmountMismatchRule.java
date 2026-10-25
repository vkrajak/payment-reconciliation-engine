package com.recon.classifier.rules;

import com.recon.classifier.config.ClassifierProperties;
import com.recon.events.exception.ExceptionCandidate;
import com.recon.events.exception.ExceptionCode;
import com.recon.events.exception.SourceSnapshot;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(40)
public class AmountMismatchRule implements ClassificationRule {

    private final ClassifierProperties props;

    public AmountMismatchRule(ClassifierProperties props) {
        this.props = props;
    }

    @Override public String name() { return "AmountMismatchRule"; }

    @Override
    public Optional<ClassificationResult> classify(ExceptionCandidate c) {
        List<SourceSnapshot> withAmount = c.events().stream().filter(e -> e.amount() != null).toList();
        long distinctSources = withAmount.stream().map(SourceSnapshot::source).distinct().count();
        if (distinctSources < 2) {
            return Optional.empty();
        }
        BigDecimal min = withAmount.stream().map(SourceSnapshot::amount).min(BigDecimal::compareTo).orElseThrow();
        BigDecimal max = withAmount.stream().map(SourceSnapshot::amount).max(BigDecimal::compareTo).orElseThrow();
        BigDecimal delta = max.subtract(min);
        if (delta.compareTo(props.tolerance()) <= 0) {
            return Optional.empty(); // within tolerance: not an exception
        }
        Map<String, String> amounts = new LinkedHashMap<>();
        withAmount.forEach(e -> amounts.put(e.source().name(), e.amount().toPlainString()));
        return Optional.of(new ClassificationResult(ExceptionCode.AMOUNT_MISMATCH, name(),
                Map.of("amountBySource", amounts,
                        "delta", delta.toPlainString(),
                        "tolerance", props.tolerance().toPlainString())));
    }
}
