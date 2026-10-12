package com.recon.engine.topology;

import com.recon.engine.config.EngineProperties;
import com.recon.engine.model.ExceptionOutcome;
import com.recon.engine.model.MatchOutcome;
import com.recon.engine.model.ReconciliationState;
import com.recon.engine.model.SourceEventEnvelope;
import com.recon.engine.model.SourceType;
import com.recon.engine.rules.ExceptionClassifier;
import com.recon.engine.rules.MatchRuleChain;
import com.recon.engine.serde.JacksonSerde;
import com.recon.events.BankEvent;
import com.recon.events.ExceptionCode;
import com.recon.events.ExceptionEvent;
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
import java.util.UUID;

/**
 * Builds the full topology onto the shared {@link StreamsBuilder} that
 * spring-kafka's @EnableKafkaStreams infrastructure provides and manages the
 * lifecycle of (see config.KafkaStreamsConfig doc). The build happens once,
 * in {@link #buildTopology()}, called from a {@code @PostConstruct} so it
 * runs during context startup before the factory bean starts the actual
 * KafkaStreams client.
 * <p>
 * Shape of the pipeline (PHASE 5: now three terminal branches, not one):
 * <pre>
 *   ledger-events (Avro) --\
 *   psp-events    (Avro) ---+--> map to SourceEventEnvelope --> merge -->
 *   bank-events   (Avro) --/
 *
 *   groupByKey().windowedBy(SessionWindows) .aggregate(...)   [3-way join, blueprint sec 5.1]
 *     --> KTable<Windowed<String>, ReconciliationState>  ("aggregated")
 *          |
 *          |-- (A) toStream(), UNSUPPRESSED -- fires the instant the aggregate changes --
 *          |     "resolved"
 *          |     |
 *          |     |-- A1: filter(conflict present) --------------------------------+
 *          |     |     classifyConflict -> DUPLICATE_TRANSACTION_REF / LATE_ARRIVAL |
 *          |     |                                                                  |
 *          |     |-- A2: filter(complete && no conflict)                            |
 *          |           mapValues(evaluate rule chain) -> MatchAttempt               |
 *          |           |-- outcome present -> recon-results (MATCHED family)        |
 *          |           |-- outcome empty -> classifyMismatch -> AMOUNT/CURRENCY_MISMATCH
 *          |                                                                        |
 *          |-- (B) suppress(untilWindowCloses).toStream() -- fires once per key,    |
 *                when the session window actually closes --                        |
 *                filter(!complete && no conflict)                                   |
 *                classifyIncomplete -> MISSING_SOURCE / ORPHAN_EVENT                |
 *                                                                                    |
 *                                                                        all exception outcomes
 *                                                                        --> exception-events
 * </pre>
 * Branch (A) resolves MATCHED/mismatch/conflict outcomes as early as possible
 * (no reason to wait for window close once we already know the answer).
 * Branch (B) exists ONLY to catch the "never became complete, and never had
 * a conflict either" case, which by definition can only be known once the
 * session window has genuinely closed -- see blueprint sec 5.1's state
 * machine and this class's git history (Phase 3 deliberately had no (B) at
 * all, since it only ever needed early resolution).
 */
@Slf4j
@Component
public class ReconciliationTopology {

    private final StreamsBuilder streamsBuilder;
    private final EngineProperties properties;
    private final MatchRuleChain matchRuleChain;
    private final ExceptionClassifier exceptionClassifier;
    private final String schemaRegistryUrl;

    public ReconciliationTopology(StreamsBuilder streamsBuilder,
                                   EngineProperties properties,
                                   MatchRuleChain matchRuleChain,
                                   ExceptionClassifier exceptionClassifier,
                                   org.springframework.core.env.Environment env) {
        this.streamsBuilder = streamsBuilder;
        this.properties = properties;
        this.matchRuleChain = matchRuleChain;
        this.exceptionClassifier = exceptionClassifier;
        this.schemaRegistryUrl = env.getProperty("spring.kafka.properties.schema.registry.url");
    }

    @PostConstruct
    public void buildTopology() {
        SpecificAvroSerde<LedgerEvent> ledgerSerde = avroSerde();
        SpecificAvroSerde<PspEvent> pspSerde = avroSerde();
        SpecificAvroSerde<BankEvent> bankSerde = avroSerde();
        SpecificAvroSerde<ReconciliationResult> resultSerde = avroSerde();
        SpecificAvroSerde<ExceptionEvent> exceptionSerde = avroSerde();

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

        // ---------- Branch A: unsuppressed, early resolution ----------
        KStream<String, ReconciliationState> resolved = aggregated
                .toStream()
                .selectKey((windowedKey, state) -> windowedKey.key());

        // A1: conflict detected (duplicate second event for a source) -- fires
        // immediately regardless of completeness, see class doc.
        resolved.filter((transactionRef, state) -> state != null && state.getConflict() != null)
                .mapValues((transactionRef, state) -> {
                    log.warn("txn_ref={} CONFLICT detected on source={}: existing eventId={} vs new eventId={} (afterComplete={})",
                            transactionRef, state.getConflict().getSource(), state.getConflict().getExistingEventId(),
                            state.getConflict().getNewEventId(), state.getConflict().isDetectedAfterComplete());
                    return exceptionClassifier.classifyConflict(state);
                })
                .map((transactionRef, outcome) -> KeyValue.pair(transactionRef, toAvroException(transactionRef, outcome)))
                .to(properties.getExceptionEventsTopic(), Produced.with(Serdes.String(), exceptionSerde));

        // A2: complete and no conflict -> the actual matching rule chain
        KStream<String, MatchAttempt> attempts = resolved
                .filter((transactionRef, state) -> state != null && state.isComplete() && state.getConflict() == null)
                .mapValues((transactionRef, state) -> new MatchAttempt(state, matchRuleChain.evaluate(state)));

        attempts.filter((transactionRef, attempt) -> attempt.outcome().isPresent())
                .mapValues(attempt -> attempt.outcome().get())
                .map((transactionRef, outcome) -> KeyValue.pair(transactionRef, toAvroResult(transactionRef, outcome)))
                .to(properties.getReconResultsTopic(), Produced.with(Serdes.String(), resultSerde));

        attempts.filter((transactionRef, attempt) -> attempt.outcome().isEmpty())
                .mapValues(attempt -> {
                    log.info("txn_ref={} failed to match (amount/currency mismatch beyond tolerance), classifying exception",
                            attempt.state().getTransactionRef());
                    return exceptionClassifier.classifyMismatch(attempt.state());
                })
                .map((transactionRef, outcome) -> KeyValue.pair(transactionRef, toAvroException(transactionRef, outcome)))
                .to(properties.getExceptionEventsTopic(), Produced.with(Serdes.String(), exceptionSerde));

        // ---------- Branch B: suppressed until window close ----------
        // Only reachable here if the window closed WITHOUT ever becoming complete
        // and WITHOUT a conflict (conflicts are already handled by A1, regardless
        // of whether the transaction ever completes -- see class doc on why this
        // phase reports at most one exception per transaction rather than layering
        // MISSING_SOURCE on top of an already-reported conflict).
        aggregated
                .suppress(Suppressed.untilWindowCloses(Suppressed.BufferConfig.unbounded()))
                .toStream()
                .selectKey((windowedKey, state) -> windowedKey.key())
                .filter((transactionRef, state) -> state != null && !state.isComplete() && state.getConflict() == null)
                .mapValues(state -> {
                    log.info("txn_ref={} session window closed incomplete, sources_present={}",
                            state.getTransactionRef(), state.sourcesPresent());
                    return exceptionClassifier.classifyIncomplete(state);
                })
                .map((transactionRef, outcome) -> KeyValue.pair(transactionRef, toAvroException(transactionRef, outcome)))
                .to(properties.getExceptionEventsTopic(), Produced.with(Serdes.String(), exceptionSerde));

        log.info("Reconciliation topology built: {} -> session-join -> [match chain | exception classifier] -> {} / {}",
                String.join(", ", properties.getLedgerTopic(), properties.getPspTopic(), properties.getBankTopic()),
                properties.getReconResultsTopic(), properties.getExceptionEventsTopic());
    }

    /** Tiny carrier so the rule-chain evaluation (which needs both the original
     *  state and its outcome) only runs once per record, with the two possible
     *  downstream paths (matched vs. mismatch-exception) branching off the same
     *  computed result rather than each re-evaluating the chain independently. */
    private record MatchAttempt(ReconciliationState state, Optional<MatchOutcome> outcome) {}

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
                // PHASE 4: resultId is a fresh UUID per emitted result -- see
                // MatchedResultListener's class doc for the full idempotency rationale.
                .setResultId(UUID.randomUUID().toString())
                .setLedgerEventId(outcome.getLedgerEventId())
                .setPspEventId(outcome.getPspEventId())
                .setBankEventId(outcome.getBankEventId())
                .build();
    }

    /** Same resultId pattern as toAvroResult, mirrored for exceptions -- see
     *  ExceptionEvent.avsc's resultId field doc. */
    private ExceptionEvent toAvroException(String transactionRef, ExceptionOutcome outcome) {
        ExceptionCode avroCode = switch (outcome.getExceptionCode()) {
            case MISSING_SOURCE -> ExceptionCode.MISSING_SOURCE;
            case AMOUNT_MISMATCH -> ExceptionCode.AMOUNT_MISMATCH;
            case CURRENCY_MISMATCH -> ExceptionCode.CURRENCY_MISMATCH;
            case DUPLICATE_TRANSACTION_REF -> ExceptionCode.DUPLICATE_TRANSACTION_REF;
            case ORPHAN_EVENT -> ExceptionCode.ORPHAN_EVENT;
            case LATE_ARRIVAL -> ExceptionCode.LATE_ARRIVAL;
        };

        return ExceptionEvent.newBuilder()
                .setTransactionRef(transactionRef)
                .setExceptionCode(avroCode)
                .setSourcesPresent(outcome.getSourcesPresent())
                .setRawLedgerEvent(outcome.getRawLedgerEvent())
                .setRawPspEvent(outcome.getRawPspEvent())
                .setRawBankEvent(outcome.getRawBankEvent())
                .setDetectedAt(Instant.now())
                .setResultId(UUID.randomUUID().toString())
                .build();
    }
}
