package com.recon.sim.psp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PspSimulatorApplication {
    public static void main(String[] args) {
        SpringApplication.run(PspSimulatorApplication.class, args);
    }
}
