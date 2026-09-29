package com.recon.sim.psp.producer;

import com.recon.events.PspEvent;
import com.recon.events.TxnType;
import com.recon.sim.common.ScenarioPlan;
import com.recon.sim.psp.config.SimulatorProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

@Slf4j
@Component
public class PspEventPublisher {

    private final KafkaTemplate<String, Object> avroKafkaTemplate;
    private final SimulatorProperties properties;

    public PspEventPublisher(KafkaTemplate<String, Object> avroKafkaTemplate, SimulatorProperties properties) {
        this.avroKafkaTemplate = avroKafkaTemplate;
        this.properties = properties;
    }

    /**
     * @param plan          the scenario driving this transaction
     * @param amountOverride the amount to actually report -- equal to plan.getAmount()
     *                        for the happy path, or plan.getPspAmountOverride() when
     *                        the scenario calls for AMOUNT_MISMATCH. Passed explicitly
     *                        rather than re-derived here, so the listener stays the
     *                        single place that decides "what amount does this
     *                        publish use" and this class stays a pure publisher.
     */
    public void publish(ScenarioPlan plan, BigDecimal amountOverride) {
        BigDecimal fee = amountOverride
                .multiply(BigDecimal.valueOf(properties.getFeeRate()))
                .setScale(2, RoundingMode.HALF_UP);

        PspEvent event = PspEvent.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setTransactionRef(plan.getTransactionRef())
                .setAccountId(plan.getAccountId())
                .setAmount(amountOverride)
                .setCurrency(plan.getCurrency())
                .setTxnType(TxnType.valueOf(plan.getTxnType()))
                .setPspFee(fee)
                .setAuthCode("AUTH-" + (100000 + new java.util.Random().nextInt(900000)))
                .setPostedAt(Instant.now())
                .setSourceSystem("PSP")
                .build();

        avroKafkaTemplate.send(properties.getTopic(), plan.getTransactionRef(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish PspEvent for txn_ref={}", plan.getTransactionRef(), ex);
                    } else {
                        log.debug("Published PspEvent txn_ref={} amount={} partition={}",
                                plan.getTransactionRef(), amountOverride, result.getRecordMetadata().partition());
                    }
                });
    }
}
