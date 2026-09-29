package com.recon.sim.ledger.config;

import io.confluent.kafka.serializers.KafkaAvroSerializer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * We deliberately run TWO producer factories in this module:
 *
 *  - "avroKafkaTemplate": publishes the real LedgerEvent Avro records to
 *    'ledger-events', serialized via Confluent's KafkaAvroSerializer against
 *    Schema Registry -- this is production-shaped.
 *
 *  - "scenarioKafkaTemplate": publishes plain JSON ScenarioPlan objects to
 *    the dev-only 'sim-scenarios' topic. Using JSON (not Avro) here is
 *    intentional: this topic never reaches production and never needs
 *    schema evolution guarantees, so we don't pollute Schema Registry with
 *    simulation-only schemas.
 */
@Configuration
public class KafkaProducerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.properties.schema.registry.url}")
    private String schemaRegistryUrl;

    @Bean
    public ProducerFactory<String, Object> avroProducerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaAvroSerializer.class);
        props.put("schema.registry.url", schemaRegistryUrl);
        // idempotent producer: acks=all + enable.idempotence avoids the producer itself
        // creating duplicate publishes on retry -- separate concern from the
        // consumer-side idempotency described in blueprint sec 4.3.
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, Object> avroKafkaTemplate(ProducerFactory<String, Object> avroProducerFactory) {
        return new KafkaTemplate<>(avroProducerFactory);
    }

    @Bean
    public ProducerFactory<String, String> scenarioProducerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, String> scenarioKafkaTemplate(ProducerFactory<String, String> scenarioProducerFactory) {
        return new KafkaTemplate<>(scenarioProducerFactory);
    }
}
