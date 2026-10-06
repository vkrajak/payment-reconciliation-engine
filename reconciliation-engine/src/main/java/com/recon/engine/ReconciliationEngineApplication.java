package com.recon.engine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.EnableKafkaStreams;

/**
 * reconciliation-engine, Phase 3 scope: the Kafka Streams topology that
 * performs the 3-way session-windowed join and matching rule chain (see
 * topology.ReconciliationTopology), plus a plain @KafkaListener sink
 * (sink.MatchedResultListener) that persists MATCHED outcomes to Postgres.
 * Idempotency-key checking, exception classification, and Redis caching are
 * NOT in this phase -- see the module pom.xml description for the full list.
 */
@SpringBootApplication
@EnableKafkaStreams
public class ReconciliationEngineApplication {
    public static void main(String[] args) {
        SpringApplication.run(ReconciliationEngineApplication.class, args);
    }
}
