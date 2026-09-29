package com.recon.sim.bank.watcher;

import com.recon.events.BankEvent;
import com.recon.events.TxnType;
import com.recon.sim.bank.config.IngesterProperties;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * THE PRODUCTION COMPONENT: everything else in this module is scaffolding to
 * generate realistic files locally, but this class's logic is exactly what
 * runs against a real bank's S3 drop -- poll for new objects, parse, publish,
 * track what's processed. Swapping LocalStack for real AWS S3 changes zero
 * lines here (only S3Config's endpoint/credentials change).
 * <p>
 * KNOWN LIMITATION (call this out explicitly, don't hide it): "already
 * processed" tracking is an in-memory {@link Set}, so a pod restart re-reads
 * every object currently in the bucket. This is safe from a DATA-CORRECTNESS
 * standpoint only because event_id is derived deterministically from
 * statement_line_id (see below) -- re-publishing the same file produces the
 * same event_ids, which the reconciliation-engine's idempotency layer
 * (blueprint sec 4.3) will no-op on. It is NOT safe from a cost/throughput
 * standpoint at scale. Production hardening (Phase 9+) replaces this with a
 * durable `processed_files` table or S3 object tags, so restarts don't
 * re-download the whole bucket.
 */
@Slf4j
@Component
public class StatementFileWatcher {

    private static final CSVFormat CSV_FORMAT = CSVFormat.DEFAULT.builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .build();

    private final S3Client s3Client;
    private final KafkaTemplate<String, Object> avroKafkaTemplate;
    private final IngesterProperties properties;

    // see KNOWN LIMITATION above
    private final Set<String> processedKeys = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public StatementFileWatcher(S3Client s3Client, KafkaTemplate<String, Object> avroKafkaTemplate, IngesterProperties properties) {
        this.s3Client = s3Client;
        this.avroKafkaTemplate = avroKafkaTemplate;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${sim.bank.watch-interval-ms}")
    public void poll() {
        ListObjectsV2Response listing = s3Client.listObjectsV2(
                ListObjectsV2Request.builder()
                        .bucket(properties.getS3Bucket())
                        .prefix(properties.getStatementKeyPrefix())
                        .build());

        for (S3Object object : listing.contents()) {
            String key = object.key();
            if (processedKeys.contains(key)) {
                continue;
            }
            try {
                processFile(key);
                processedKeys.add(key);
            } catch (Exception e) {
                // deliberately NOT added to processedKeys -- next poll retries the whole
                // file. Fine for a batch file (idempotent per-line via deterministic
                // event_id); a partial-publish-then-crash mid-file just re-publishes
                // some lines, which is a safe no-op downstream.
                log.error("Failed to process statement file s3://{}/{}, will retry next poll",
                        properties.getS3Bucket(), key, e);
            }
        }
    }

    private void processFile(String key) throws Exception {
        ResponseInputStream<GetObjectResponse> object = s3Client.getObject(
                GetObjectRequest.builder().bucket(properties.getS3Bucket()).key(key).build());

        int published = 0;
        try (CSVParser parser = CSVParser.parse(new InputStreamReader(object, StandardCharsets.UTF_8), CSV_FORMAT)) {
            for (CSVRecord record : parser) {
                publishBankEvent(record, key);
                published++;
            }
        }
        log.info("Processed s3://{}/{}: published {} BankEvent(s)", properties.getS3Bucket(), key, published);
    }

    private void publishBankEvent(CSVRecord record, String sourceFileKey) {
        String statementLineId = record.get("statement_line_id");
        String transactionRef = record.get("transaction_ref");

        // DETERMINISTIC event_id derivation -- the whole point being: re-processing
        // the same file (see KNOWN LIMITATION above) produces the SAME event_id for
        // the same line every time, so it's a safe no-op through the idempotency
        // layer rather than a phantom duplicate. See BankEvent.avsc doc on event_id.
        String eventId = UUID.nameUUIDFromBytes(statementLineId.getBytes(StandardCharsets.UTF_8)).toString();

        BankEvent event = BankEvent.newBuilder()
                .setEventId(eventId)
                .setTransactionRef(transactionRef)
                .setAccountId(record.get("account_id"))
                .setAmount(new BigDecimal(record.get("amount")))
                .setCurrency(record.get("currency"))
                .setTxnType(TxnType.valueOf(record.get("txn_type")))
                .setStatementLineId(statementLineId)
                .setValueDate(LocalDate.parse(record.get("value_date")))
                .setSourceFile(sourceFileKey)
                .setSourceSystem("BANK")
                .build();

        avroKafkaTemplate.send(properties.getBankEventsTopic(), transactionRef, event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish BankEvent for txn_ref={} (line={})", transactionRef, statementLineId, ex);
                    }
                });
    }
}
