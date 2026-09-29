package com.recon.sim.psp.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recon.sim.common.ParticipantBehavior;
import com.recon.sim.common.ScenarioPlan;
import com.recon.sim.psp.producer.PspEventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Consumes {@link ScenarioPlan} JSON from 'sim-scenarios' and realizes
 * plan.pspBehavior() as an actual (or deliberately absent) PspEvent publish.
 * See {@link ParticipantBehavior} for the full behavior -&gt; exception mapping.
 */
@Slf4j
@Component
public class ScenarioPlanListener {

    private final ObjectMapper objectMapper;
    private final PspEventPublisher publisher;
    private final TaskScheduler taskScheduler;

    public ScenarioPlanListener(ObjectMapper objectMapper, PspEventPublisher publisher, TaskScheduler taskScheduler) {
        this.objectMapper = objectMapper;
        this.publisher = publisher;
        this.taskScheduler = taskScheduler;
    }

    @KafkaListener(topics = "${sim.psp.scenario-topic}", containerFactory = "scenarioKafkaListenerContainerFactory")
    public void onScenario(String scenarioJson) {
        ScenarioPlan plan;
        try {
            plan = objectMapper.readValue(scenarioJson, ScenarioPlan.class);
        } catch (Exception e) {
            log.error("Failed to deserialize ScenarioPlan, dropping: {}", scenarioJson, e);
            return;
        }

        ParticipantBehavior behavior = plan.getPspBehavior();
        switch (behavior) {
            case EMIT_MATCHING -> publisher.publish(plan, plan.getAmount());

            case EMIT_MISMATCHED_AMOUNT -> publisher.publish(plan, plan.getPspAmountOverride());

            case SKIP -> log.info("txn_ref={} PSP behavior=SKIP -- deliberately not publishing (MISSING_SOURCE scenario)",
                    plan.getTransactionRef());

            case EMIT_DUPLICATE -> {
                // two independent publishes, two independent event_ids, same transaction_ref+amount.
                // Exercises reconciliation-engine's DUPLICATE_TRANSACTION_REF / idempotency path (blueprint sec 4.3 & 6).
                publisher.publish(plan, plan.getAmount());
                publisher.publish(plan, plan.getAmount());
            }

            case EMIT_DELAYED -> {
                long delayMs = plan.getPspDelayMs();
                log.info("txn_ref={} PSP behavior=EMIT_DELAYED, scheduling publish in {}ms",
                        plan.getTransactionRef(), delayMs);
                taskScheduler.schedule(() -> publisher.publish(plan, plan.getAmount()),
                        Instant.now().plusMillis(delayMs));
            }
        }
    }
}
