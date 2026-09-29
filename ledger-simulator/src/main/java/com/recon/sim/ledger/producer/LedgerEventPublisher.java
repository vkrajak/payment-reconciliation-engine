package com.recon.sim.ledger.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recon.events.LedgerEvent;
import com.recon.events.TxnType;
import com.recon.sim.common.ScenarioPlan;
import com.recon.sim.ledger.config.SimulatorProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Slf4j
@Component
public class LedgerEventPublisher {

    private final KafkaTemplate<String, Object> avroKafkaTemplate;
    private final KafkaTemplate<String, String> scenarioKafkaTemplate;
    private final SimulatorProperties properties;
    private final ObjectMapper objectMapper;

    public LedgerEventPublisher(KafkaTemplate<String, Object> avroKafkaTemplate,
                                 KafkaTemplate<String, String> scenarioKafkaTemplate,
                                 SimulatorProperties properties,
                                 ObjectMapper objectMapper) {
        this.avroKafkaTemplate = avroKafkaTemplate;
        this.scenarioKafkaTemplate = scenarioKafkaTemplate;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * Publishes the scenario coordination message FIRST, then the LedgerEvent
     * itself (if ledgerEmits=true). Order matters in the happy-path sense --
     * psp-simulator / bank-file-ingester don't strictly need to see the
     * scenario before the ledger event for correctness (they key off
     * transaction_ref independently) but publishing the plan first means a
     * human watching Kafka UI sees the "intent" appear before the events
     * that realize it, which is a lot easier to debug against.
     */
    public void publish(ScenarioPlan plan) {
        publishScenarioPlan(plan);

        if (!plan.isLedgerEmits()) {
            log.info("txn_ref={} scenario says ledger does NOT emit (orphan-style scenario) -- skipping LedgerEvent",
                    plan.getTransactionRef());
            return;
        }

        LedgerEvent event = LedgerEvent.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setTransactionRef(plan.getTransactionRef())
                .setAccountId(plan.getAccountId())
                .setAmount(plan.getAmount())
                .setCurrency(plan.getCurrency())
                .setTxnType(TxnType.valueOf(plan.getTxnType()))
                .setPostedAt(Instant.ofEpochMilli(plan.getLedgerPostedAtEpochMillis()))
                .setSourceSystem("LEDGER")
                .build();

        avroKafkaTemplate.send(properties.getTopic(), plan.getTransactionRef(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish LedgerEvent for txn_ref={}", plan.getTransactionRef(), ex);
                    } else {
                        log.debug("Published LedgerEvent txn_ref={} partition={}",
                                plan.getTransactionRef(), result.getRecordMetadata().partition());
                    }
                });
    }

    private void publishScenarioPlan(ScenarioPlan plan) {
        try {
            String json = objectMapper.writeValueAsString(plan);
            scenarioKafkaTemplate.send(properties.getScenarioTopic(), plan.getTransactionRef(), json)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("Failed to publish ScenarioPlan for txn_ref={}", plan.getTransactionRef(), ex);
                        }
                    });
        } catch (Exception e) {
            log.error("Failed to serialize ScenarioPlan for txn_ref={}", plan.getTransactionRef(), e);
        }
    }
}
