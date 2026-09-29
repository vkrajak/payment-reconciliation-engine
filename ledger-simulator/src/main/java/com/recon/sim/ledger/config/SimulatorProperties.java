package com.recon.sim.ledger.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Binds the `sim.ledger.*` block from application.yml. Kept as a proper
 * typed properties class (rather than scattered @Value fields) because the
 * scenario weights in particular are something you'll want to tune per
 * environment (e.g. crank up amount-mismatch weight when demoing the
 * exception dashboard) without touching code.
 */
@Component
@ConfigurationProperties(prefix = "sim.ledger")
@Data
public class SimulatorProperties {

    private String topic;
    private String scenarioTopic;
    private int txnsPerTick;
    private long tickIntervalMs;
    private Weights weights = new Weights();

    @Data
    public static class Weights {
        private int happyPath;
        private int amountMismatch;
        private int missingPsp;
        private int missingBank;
        private int duplicatePsp;
        private int duplicateBank;
        private int delayedBank;
    }
}
