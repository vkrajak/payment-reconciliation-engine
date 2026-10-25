package com.recon.classifier.rules;

import com.recon.events.exception.ExceptionCode;
import java.util.Map;

public record ClassificationResult(ExceptionCode code, String ruleName, Map<String, Object> detail) {
}
