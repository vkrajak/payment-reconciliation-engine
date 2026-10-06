package com.recon.engine.config;

import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.KafkaStreamsDefaultConfiguration;
import org.springframework.kafka.config.KafkaStreamsConfiguration;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaStreamsConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.properties.schema.registry.url}")
    private String schemaRegistryUrl;

    /**
     * Bean name is mandated by spring-kafka's @EnableKafkaStreams -- it looks up
     * exactly this name (KafkaStreamsDefaultConfiguration.DEFAULT_STREAMS_CONFIG_BEAN_NAME)
     * to auto-configure the StreamsBuilderFactoryBean / StreamsBuilder that
     * topology.ReconciliationTopology gets injected with.
     */
    @Bean(name = KafkaStreamsDefaultConfiguration.DEFAULT_STREAMS_CONFIG_BEAN_NAME)
    public KafkaStreamsConfiguration kStreamsConfig(EngineProperties properties) {
        Map<String, Object> props = new HashMap<>();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, properties.getApplicationId());
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.StringSerde.class);
        // no default VALUE serde -- each source topic carries a different Avro type
        // and is given its own SpecificAvroSerde explicitly in ReconciliationTopology.
        props.put("schema.registry.url", schemaRegistryUrl);
        props.put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, 1000);       // fast commit for a snappy dev/demo feedback loop
        props.put(StreamsConfig.REPLICATION_FACTOR_CONFIG, 1);          // single-broker dev cluster (Phase 0)
        props.put(StreamsConfig.NUM_STREAM_THREADS_CONFIG, 2);
        // dev-only: start from earliest so a freshly (re)started engine picks up
        // events published before it came up, instead of only new ones.
        props.put(StreamsConfig.consumerPrefix(org.apache.kafka.clients.consumer.ConsumerConfig.AUTO_OFFSET_RESET_CONFIG), "earliest");
        return new KafkaStreamsConfiguration(props);
    }
}
