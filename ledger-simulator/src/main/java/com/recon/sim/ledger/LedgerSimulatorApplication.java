package com.recon.sim.ledger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * ledger-simulator: owns the scenario decision for every simulated
 * transaction (see simulation-common.ScenarioPlan) and publishes the
 * LedgerEvent side of it. Run standalone; independently deployable, exactly
 * like the real ledger service it stands in for.
 */
@SpringBootApplication
@EnableScheduling
public class LedgerSimulatorApplication {
    public static void main(String[] args) {
        SpringApplication.run(LedgerSimulatorApplication.class, args);
    }
}
