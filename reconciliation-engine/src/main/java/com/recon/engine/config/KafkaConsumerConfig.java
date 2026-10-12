package com.recon.engine.config;

import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

import java.util.HashMap;
import java.util.Map;

/**
 * Powers {@link com.recon.engine.sink.MatchedResultListener}'s plain
 * {@code @KafkaListener} -- intentionally separate from the Kafka Streams
 * client configured in {@link KafkaStreamsConfig}. The Streams topology
 * reads/writes ledger-events/psp-events/bank-events/recon-results using its
 * own StreamsConfig + per-stream SpecificAvroSerde instances; this listener
 * is a completely independent consumer group reading only recon-results, so
 * the sink can be scaled, restarted, or even extracted into its own
 * deployable later without touching the topology at all.
 * <p>
 * PHASE 5 ADDITION: DLQ wiring, per blueprint sec 6's explicit distinction --
 * this handles TECHNICAL failures in the listener itself (a DB write that
 * keeps failing, a connectivity blip), never business exceptions, which are
 * a completely different path (ExceptionClassifier -> exception-events ->
 * exception-classifier, not this at all). 3 retries with exponential backoff
 * (1s, 2s, 4s) matches blueprint's number exactly; after that, the record
 * publishes to "recon-results.DLT" (see infra/kafka/create-topics.sh) and
 * processing moves on rather than blocking the partition forever.
 */
@Configuration
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.properties.schema.registry.url}")
    private String schemaRegistryUrl;

    @Bean
    public ConsumerFactory<String, Object> avroConsumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, KafkaAvroDeserializer.class);
        props.put("schema.registry.url", schemaRegistryUrl);
        props.put("specific.avro.reader", true);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return new DefaultKafkaConsumerFactory<>(props);
    }

    /** Used only by the DLQ recoverer below, to republish a failed record's
     *  original (Avro) value onto its ".DLT" topic -- a completely separate
     *  concern from ReconciliationTopology's own Avro producers. */
    @Bean
    public KafkaTemplate<String, Object> dlqKafkaTemplate() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaAvroSerializer.class);
        props.put("schema.registry.url", schemaRegistryUrl);
        ProducerFactory<String, Object> producerFactory = new DefaultKafkaProducerFactory<>(props);
        return new KafkaTemplate<>(producerFactory);
    }

    @Bean
    public DefaultErrorHandler dlqErrorHandler(KafkaTemplate<String, Object> dlqKafkaTemplate) {
        // destination resolver: same topic name + ".DLT" suffix, same partition number
        // -- matches the naming convention already used for exception-events.DLT (Phase 0).
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(dlqKafkaTemplate,
                (record, ex) -> new org.apache.kafka.common.TopicPartition(record.topic() + ".DLT", record.partition()));

        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(3);
        backOff.setInitialInterval(1000L);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(10_000L);

        return new DefaultErrorHandler(recoverer, backOff);
    }

    /** Bean name "kafkaListenerContainerFactory" is the default spring-kafka
     *  looks for when a @KafkaListener doesn't specify containerFactory explicitly. */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(
            ConsumerFactory<String, Object> avroConsumerFactory, DefaultErrorHandler dlqErrorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(avroConsumerFactory);
        factory.setCommonErrorHandler(dlqErrorHandler);
        return factory;
    }
}
