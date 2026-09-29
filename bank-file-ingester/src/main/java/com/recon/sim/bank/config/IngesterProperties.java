package com.recon.sim.bank.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "sim.bank")
@Data
public class IngesterProperties {
    private String bankEventsTopic;      // bank-events (produced)
    private String scenarioTopic;        // sim-scenarios (consumed)
    private String consumerGroupId;      // bank-file-ingester group (watcher has no Kafka consumer -- S3 polling only)

    private String s3Bucket;             // bank-statements
    private String s3Endpoint;           // http://localhost:4566 (LocalStack)
    private String s3Region;             // us-east-1
    private String s3AccessKey;
    private String s3SecretKey;
    private String statementKeyPrefix;   // "statements/"

    private long writeIntervalMs;        // how often StatementFileWriter flushes ready lines to a new S3 file
    private long watchIntervalMs;        // how often StatementFileWatcher polls the bucket for new files
}
