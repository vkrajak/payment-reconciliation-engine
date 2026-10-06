package com.recon.engine.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * The one shape every source event gets mapped into before entering the
 * session-windowed aggregation. This is what lets ledgerStream, pspStream
 * and bankStream be {@code .merge()}d into a single KStream despite having
 * three different Avro types -- the topology only ever deals with this type
 * from the merge point onward. Serialized to/from JSON only inside the
 * Streams state store's changelog (see serde.JacksonSerde); never published
 * to a real Kafka topic other than that internal changelog.
 */
@Value
@Builder
@Jacksonized
@JsonIgnoreProperties(ignoreUnknown = true)
public class SourceEventEnvelope {
    SourceType source;
    String transactionRef;
    String accountId;
    BigDecimal amount;
    String currency;
    String txnType;
    String eventId;
    Instant postedAt;
    LocalDate valueDate;   // non-null only when source == BANK
}
