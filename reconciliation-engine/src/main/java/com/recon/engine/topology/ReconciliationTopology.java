package com.recon.engine.topology;

import com.recon.engine.config.EngineProperties;
import com.recon.engine.model.MatchOutcome;
import com.recon.engine.model.ReconciliationState;
import com.recon.engine.model.SourceEventEnvelope;
import com.recon.engine.model.SourceType;
import com.recon.engine.rules.MatchRuleChain;
import com.recon.engine.serde.JacksonSerde;
import com.recon.events.BankEvent;
import com.recon.events.LedgerEvent;
import com.recon.events.MatchStatus;
import com.recon.events.PspEvent;
import com.recon.events.ReconciliationResult;
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.*;
import org.apache.kafka.streams.state.SessionStore;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Builds the full Phase 3 topology onto the shared {@link StreamsBuilder}
 * that spring-kafka's @EnableKafkaStreams infrastructure provides and
 * manages the lifecycle of (see config.KafkaStreamsConfig doc). The build
 * happens once, in {@link #buildTopology()}, called from a
 * {@code @PostConstruct} so it runs during context startup before the
 * factory bean starts the actual KafkaStreams client.
 * <p>
 * Shape of the pipeline:
 * <pre>
 *   ledger-events (Avro) --\
 *   psp-events    (Avro) ---+--> map to SourceEventEnvelope --> merge -->
 *   bank-events   (Avro) --/
 *
 *   groupByKey().windowedBy(SessionWindows) .aggregate(...)   [3-way join, blueprint sec 5.1]
 *     --> KTable<Windowed<String>, ReconciliationState>
 *     --> toStream().selectKey(drop window wrapper)
 *     --> filter(state.isComplete())
 *     --> mapValues(MatchRuleChain::evaluate)                 [blueprint sec 5.2]
 *     --> filter(present) .mapValues(Optional::get)
 *     --> map to Avro ReconciliationResult
 *     --> to(recon-results)
 * </pre>
 * Incomplete or non-matching states are filtered out and logged, not routed
 * anywhere -- Phase 5 adds the exception-event branch here.
 */
@Slf4j
@Component
public class ReconciliationTopology {

    private final StreamsBuilder streamsBuilder;
    private final EngineProperties properties;
    private final MatchRuleChain matchRuleChain;
    private final String schemaRegistryUrl;

    public ReconciliationTopology(StreamsBuilder streamsBuilder,
                                   EngineProperties properties,
                                   MatchRuleChain matchRuleChain,
                                   org.springframework.core.env.Environment env) {
        this.streamsBuilder = streamsBuilder;
        this.properties = properties;
        this.matchRuleChain = matchRuleChain;
        this.schemaRegistryUrl = env.getProperty("spring.kafka.properties.schema.registry.url");
    }

    @PostConstruct
    public void buildTopology() {
        SpecificAvroSerde<LedgerEvent> ledgerSerde = avroSerde();
        SpecificAvroSerde<PspEvent> pspSerde = avroSerde();
        SpecificAvroSerde<BankEvent> bankSerde = avroSerde();
        SpecificAvroSerde<ReconciliationResult> resultSerde = avroSerde();

        KStream<String, SourceEventEnvelope> ledgerEnvelopes = streamsBuilder
                .stream(properties.getLedgerTopic(), Consumed.with(Serdes.String(), ledgerSerde))
                .mapValues(this::toEnvelope);

        KStream<String, SourceEventEnvelope> pspEnvelopes = streamsBuilder
                .stream(properties.getPspTopic(), Consumed.with(Serdes.String(), pspSerde))
                .mapValues(this::toEnvelope);

        KStream<String, SourceEventEnvelope> bankEnvelopes = streamsBuilder
                .stream(properties.getBankTopic(), Consumed.with(Serdes.String(), bankSerde))
                .mapValues(this::toEnvelope);

        KStream<String, SourceEventEnvelope> merged = ledgerEnvelopes.merge(pspEnvelopes).merge(bankEnvelopes);

        JacksonSerde<ReconciliationState> stateSerde = new JacksonSerde<>(ReconciliationState.class);

        KTable<Windowed<String>, ReconciliationState> aggregated = merged
                .groupByKey(Grouped.with(Serdes.String(), new JacksonSerde<>(SourceEventEnvelope.class)))
                .windowedBy(SessionWindows.ofInactivityGapAndGrace(
                        Duration.ofMinutes(properties.getSessionWindowMinutes()),
                        Duration.ofMinutes(properties.getSessionGraceMinutes())))
                .aggregate(
                        ReconciliationState::empty,
                        (key, envelope, aggregate) -> aggregate.withEnvelope(envelope),
                        (key, aggOne, aggTwo) -> aggOne.mergeWith(aggTwo),
                        Materialized.<String, ReconciliationState, SessionStore<Bytes, byte[]>>as(properties.getStateStoreName())
                                .withKeySerde(Serdes.String())
                                .withValueSerde(stateSerde)
                );

        aggregated
                .toStream()
                .selectKey((windowedKey, state) -> windowedKey.key())
                .filter((transactionRef, state) -> {
                    boolean complete = state != null && state.isComplete();
                    if (!complete) {
                        log.debug("txn_ref={} not yet complete, waiting for more sources", transactionRef);
                    }
                    return complete;
                })
                .mapValues((transactionRef, state) -> {
                    Optional<MatchOutcome> outcome = matchRuleChain.evaluate(state);
                    if (outcome.isEmpty()) {
                        // Phase 3 has no exception path yet -- see class doc / Phase 5.
                        log.warn("txn_ref={} failed to match (amount/currency mismatch beyond tolerance) -- " +
                                "would raise AMOUNT_MISMATCH or CURRENCY_MISMATCH in Phase 5, dropped for now", transactionRef);
                    }
                    return outcome;
                })
                .filter((transactionRef, outcome) -> outcome.isPresent())
                .mapValues(Optional::get)
                .map((transactionRef, outcome) -> KeyValue.pair(transactionRef, toAvroResult(transactionRef, outcome)))
                .to(properties.getReconResultsTopic(), Produced.with(Serdes.String(), resultSerde));

        log.info("Reconciliation topology built: {} -> session-join -> rule chain -> {}",
                String.join(", ", properties.getLedgerTopic(), properties.getPspTopic(), properties.getBankTopic()),
                properties.getReconResultsTopic());
    }

    private <T extends org.apache.avro.specific.SpecificRecord> SpecificAvroSerde<T> avroSerde() {
        SpecificAvroSerde<T> serde = new SpecificAvroSerde<>();
        Map<String, Object> config = new HashMap<>();
        config.put("schema.registry.url", schemaRegistryUrl);
        config.put("specific.avro.reader", true);
        serde.configure(config, false); // false = value serde, not key serde
        return serde;
    }

    private SourceEventEnvelope toEnvelope(LedgerEvent event) {
        return SourceEventEnvelope.builder()
                .source(SourceType.LEDGER)
                .transactionRef(event.getTransactionRef())
                .accountId(event.getAccountId())
                .amount(event.getAmount())
                .currency(event.getCurrency())
                .txnType(event.getTxnType().name())
                .eventId(event.getEventId())
                .postedAt(event.getPostedAt())
                .build();
    }

    private SourceEventEnvelope toEnvelope(PspEvent event) {
        return SourceEventEnvelope.builder()
                .source(SourceType.PSP)
                .transactionRef(event.getTransactionRef())
                .accountId(event.getAccountId())
                .amount(event.getAmount())
                .currency(event.getCurrency())
                .txnType(event.getTxnType().name())
                .eventId(event.getEventId())
                .postedAt(event.getPostedAt())
                .build();
    }

    private SourceEventEnvelope toEnvelope(BankEvent event) {
        return SourceEventEnvelope.builder()
                .source(SourceType.BANK)
                .transactionRef(event.getTransactionRef())
                .accountId(event.getAccountId())
                .amount(event.getAmount())
                .currency(event.getCurrency())
                .txnType(event.getTxnType().name())
                .eventId(event.getEventId())
                .postedAt(event.getValueDate().atStartOfDay(java.time.ZoneOffset.UTC).toInstant())
                .valueDate(event.getValueDate())
                .build();
    }

    /** Maps our internal domain enum (MatchOutcome.MatchStatus) to the Avro wire
     *  enum (com.recon.events.MatchStatus) -- kept as an explicit, single place
     *  of translation so the rule-evaluation code never depends on Avro types. */
    private ReconciliationResult toAvroResult(String transactionRef, MatchOutcome outcome) {
        MatchStatus avroStatus = switch (outcome.getMatchStatus()) {
            case MATCHED -> MatchStatus.MATCHED;
            case MATCHED_WITH_TOLERANCE -> MatchStatus.MATCHED_WITH_TOLERANCE;
            case SETTLEMENT_LAG_WARNING -> MatchStatus.SETTLEMENT_LAG_WARNING;
        };

        return ReconciliationResult.newBuilder()
                .setTransactionRef(transactionRef)
                .setMatchStatus(avroStatus)
                .setLedgerAmount(outcome.getLedgerAmount())
                .setPspAmount(outcome.getPspAmount())
                .setBankAmount(outcome.getBankAmount())
                .setCurrency(outcome.getCurrency())
                .setSettlementLagMs(outcome.getSettlementLagMs())
                .setMatchedAt(Instant.now())
                .setMatchVersion(1)
                .build();
    }
}
