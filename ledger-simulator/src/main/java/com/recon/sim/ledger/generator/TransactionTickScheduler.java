package com.recon.sim.ledger.generator;

import com.recon.sim.common.ScenarioPlan;
import com.recon.sim.ledger.config.SimulatorProperties;
import com.recon.sim.ledger.producer.LedgerEventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The only "driver" in this module: every {@code sim.ledger.tick-interval-ms}
 * it generates {@code sim.ledger.txns-per-tick} new scenarios and publishes
 * each one. Kept deliberately dumb (a for-loop on a fixed-delay schedule) --
 * this is a data generator, not a system under test, so there is no value in
 * a fancier rate-limiting or backpressure scheme here.
 */
@Slf4j
@Component
public class TransactionTickScheduler {

    private final ScenarioFactory scenarioFactory;
    private final LedgerEventPublisher publisher;
    private final SimulatorProperties properties;

    public TransactionTickScheduler(ScenarioFactory scenarioFactory,
                                     LedgerEventPublisher publisher,
                                     SimulatorProperties properties) {
        this.scenarioFactory = scenarioFactory;
        this.publisher = publisher;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${sim.ledger.tick-interval-ms}")
    public void tick() {
        int count = properties.getTxnsPerTick();
        for (int i = 0; i < count; i++) {
            ScenarioPlan plan = scenarioFactory.next();
            publisher.publish(plan);
        }
        log.info("Tick complete: published {} scenario(s)", count);
    }
}
