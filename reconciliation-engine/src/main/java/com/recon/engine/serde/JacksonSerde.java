package com.recon.engine.serde;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serializer;

import java.nio.charset.StandardCharsets;

/**
 * Minimal Serde for internal, Streams-only values (the session-window
 * aggregate's changelog). Deliberately NOT registered with Schema Registry
 * and NOT Avro -- this type ({@code ReconciliationState}) never appears on a
 * real Kafka topic a downstream service consumes, only in this topology's
 * own internal changelog, so plain JSON is simpler and sufficient here.
 */
public class JacksonSerde<T> implements Serde<T> {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    private final Class<T> targetType;

    public JacksonSerde(Class<T> targetType) {
        this.targetType = targetType;
    }

    @Override
    public Serializer<T> serializer() {
        return (topic, data) -> {
            if (data == null) {
                return null;
            }
            try {
                return MAPPER.writeValueAsBytes(data);
            } catch (Exception e) {
                throw new RuntimeException("Failed to serialize " + targetType.getSimpleName(), e);
            }
        };
    }

    @Override
    public Deserializer<T> deserializer() {
        return (topic, bytes) -> {
            if (bytes == null) {
                return null;
            }
            try {
                return MAPPER.readValue(new String(bytes, StandardCharsets.UTF_8), targetType);
            } catch (Exception e) {
                throw new RuntimeException("Failed to deserialize " + targetType.getSimpleName(), e);
            }
        };
    }
}
