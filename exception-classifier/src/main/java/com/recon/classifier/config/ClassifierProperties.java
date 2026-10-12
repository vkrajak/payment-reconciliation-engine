package com.recon.classifier.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "recon.classifier")
@Data
public class ClassifierProperties {
    private String exceptionEventsTopic;   // exception-events (consumed)
    private String consumerGroupId;
}
