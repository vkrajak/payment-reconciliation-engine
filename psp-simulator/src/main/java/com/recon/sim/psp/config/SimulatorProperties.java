package com.recon.sim.psp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "sim.psp")
@Data
public class SimulatorProperties {
    private String topic;              // psp-events
    private String scenarioTopic;      // sim-scenarios (consumed)
    private String consumerGroupId;    // psp-simulator group
    private double feeRate;            // e.g. 0.015 = 1.5% processor fee, informational only (blueprint sec 4.2)
}
