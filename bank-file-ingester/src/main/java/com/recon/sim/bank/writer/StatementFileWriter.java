package com.recon.sim.bank.writer;

import com.recon.sim.bank.config.IngesterProperties;
import com.recon.sim.bank.model.PendingBankLine;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Plays the role of "the bank": every {@code sim.bank.write-interval-ms} it
 * drains whatever pending lines are ready (see {@link PendingLineBuffer}) and
 * writes them as one CSV file to S3. If nothing is ready, it writes nothing
 * -- an empty statement file every tick would be noise, and real banks don't
 * send empty files either.
 * <p>
 * This entire class is simulation-only. It has no equivalent when a real
 * bank feed replaces LocalStack; only {@link com.recon.sim.bank.watcher.StatementFileWatcher}
 * carries over.
 */
@Slf4j
@Component
public class StatementFileWriter {

    private static final CSVFormat CSV_FORMAT = CSVFormat.DEFAULT.builder()
            .setHeader("statement_line_id", "transaction_ref", "account_id", "amount", "currency", "txn_type", "value_date")
            .build();

    private static final DateTimeFormatter KEY_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss")
            .withZone(java.time.ZoneOffset.UTC);

    private final PendingLineBuffer buffer;
    private final S3Client s3Client;
    private final IngesterProperties properties;

    public StatementFileWriter(PendingLineBuffer buffer, S3Client s3Client, IngesterProperties properties) {
        this.buffer = buffer;
        this.s3Client = s3Client;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${sim.bank.write-interval-ms}")
    public void flush() {
        List<PendingBankLine> ready = buffer.drainReady(Instant.now().toEpochMilli());
        if (ready.isEmpty()) {
            return;
        }

        String key = properties.getStatementKeyPrefix() + KEY_TIMESTAMP.format(Instant.now()) + ".csv";
        String csv = toCsv(ready);

        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(properties.getS3Bucket())
                        .key(key)
                        .contentType("text/csv")
                        .build(),
                RequestBody.fromString(csv, StandardCharsets.UTF_8)
        );

        log.info("Wrote statement file s3://{}/{} with {} line(s)", properties.getS3Bucket(), key, ready.size());
    }

    private String toCsv(List<PendingBankLine> lines) {
        StringWriter sw = new StringWriter();
        try (CSVPrinter printer = new CSVPrinter(sw, CSV_FORMAT)) {
            for (PendingBankLine line : lines) {
                printer.printRecord(
                        line.statementLineId(),
                        line.transactionRef(),
                        line.accountId(),
                        line.amount().toPlainString(),
                        line.currency(),
                        line.txnType(),
                        line.valueDate().toString()
                );
            }
        } catch (Exception e) {
            // StringWriter/CSVPrinter over a String target does not throw IO errors in
            // practice; wrapped as unchecked so callers don't need a checked-exception path.
            throw new RuntimeException("Failed to render statement CSV", e);
        }
        return sw.toString();
    }
}
