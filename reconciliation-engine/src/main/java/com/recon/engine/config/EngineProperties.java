package com.recon.engine.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
@ConfigurationProperties(prefix = "recon.engine")
@Data
public class EngineProperties {

    private String applicationId;          // Kafka Streams app.id -- also the consumer group id for the topology
    private String ledgerTopic;            // ledger-events
    private String pspTopic;               // psp-events
    private String bankTopic;              // bank-events
    private String reconResultsTopic;      // recon-results
    private String stateStoreName;         // name of the session-window aggregate's state store

    private long sessionWindowMinutes;     // blueprint sec 5.1: 30 min default
    private long sessionGraceMinutes;      // how long after window inactivity Streams still accepts late events

    private BigDecimal toleranceAmount;    // blueprint sec 5.2 rule 2: max |amount diff| still considered a match
    private long settlementLagWarningThresholdMs; // blueprint sec 5.2 rule 3: posted_at vs value_date gap that
                                                    // downgrades a MATCHED result to SETTLEMENT_LAG_WARNING
}
