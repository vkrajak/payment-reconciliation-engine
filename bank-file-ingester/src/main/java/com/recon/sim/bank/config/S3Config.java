package com.recon.sim.bank.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

import java.net.URI;

/**
 * Dev-mode S3 setup: points at LocalStack instead of real AWS. In a
 * production deployment, only s3.endpoint / credentials / region change
 * (real AWS creds via IAM role, no explicit endpointOverride) -- the rest of
 * this module's S3 interaction code is unaffected. Documented explicitly
 * because "how do you move this off LocalStack" is a fair interview
 * follow-up to expect.
 */
@Slf4j
@Configuration
public class S3Config {

    @Bean
    public S3Client s3Client(IngesterProperties properties) {
        return S3Client.builder()
                .endpointOverride(URI.create(properties.getS3Endpoint()))
                .region(Region.of(properties.getS3Region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(properties.getS3AccessKey(), properties.getS3SecretKey())))
                // path-style access is required for LocalStack; real AWS defaults to virtual-hosted style
                .forcePathStyle(true)
                .build();
    }

    /** Creates the statement bucket on startup if it doesn't already exist -- keeps
     *  "docker compose up" + "mvn spring-boot:run" a one-command dev loop with no
     *  manual `aws s3 mb` step required. */
    @Bean
    public CommandLineRunner ensureBucketExists(S3Client s3Client, IngesterProperties properties) {
        return args -> {
            String bucket = properties.getS3Bucket();
            try {
                s3Client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
                log.info("S3 bucket '{}' already exists", bucket);
            } catch (NoSuchBucketException e) {
                log.info("S3 bucket '{}' not found, creating it", bucket);
                s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
            }
        };
    }
}
