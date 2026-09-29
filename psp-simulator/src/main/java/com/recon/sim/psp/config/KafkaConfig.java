package com.recon.sim.psp.config;

import io.confluent.kafka.serializers.KafkaAvroSerializer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.*;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.properties.schema.registry.url}")
    private String schemaRegistryUrl;

    // ---- Avro producer: publishes the real PspEvent to psp-events ----

    @Bean
    public ProducerFactory<String, Object> avroProducerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaAvroSerializer.class);
        props.put("schema.registry.url", schemaRegistryUrl);
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, Object> avroKafkaTemplate(ProducerFactory<String, Object> avroProducerFactory) {
        return new KafkaTemplate<>(avroProducerFactory);
    }

    // ---- Plain-string consumer: reads ScenarioPlan JSON off sim-scenarios.
    //      Deserialized manually (ObjectMapper) in ScenarioPlanListener rather
    //      than via Spring's JsonDeserializer, because the producer
    //      (ledger-simulator) writes plain JSON with no __TypeId__ header --
    //      keeping both sides on plain String avoids header-mapping config
    //      entirely and is easier to inspect in Kafka UI as a human. ----

    @Bean
    public ConsumerFactory<String, String> scenarioConsumerFactory(SimulatorProperties properties) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, properties.getConsumerGroupId());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest"); // dev-only stream, no need to replay history on restart
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> scenarioKafkaListenerContainerFactory(
            ConsumerFactory<String, String> scenarioConsumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(scenarioConsumerFactory);
        return factory;
    }

    // ---- Scheduler used only for ParticipantBehavior.EMIT_DELAYED: schedules
    //      a one-off publish at (now + pspDelayMs) instead of blocking the
    //      listener thread with Thread.sleep(). ----

    @Bean
    public TaskScheduler pspTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("psp-delay-");
        scheduler.initialize();
        return scheduler;
    }
}
