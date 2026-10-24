package com.recon.engine.cache;

import com.recon.events.cache.RedisKeys;
import java.util.Set;
import java.util.TreeSet;
import org.apache.kafka.streams.KafkaStreams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.config.StreamsBuilderFactoryBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Writes engine:heartbeat:{partition} (TTL 30s) for every partition this instance actively processes.
 * A missing key on the dashboard means "nobody is processing that partition".
 * Assumes Spring's StreamsBuilderFactoryBean (@EnableKafkaStreams); silently idle otherwise.
 */
@Component
public class EngineHeartbeat {

    private static final Logger log = LoggerFactory.getLogger(EngineHeartbeat.class);

    private final StringRedisTemplate redis;
    private final ObjectProvider<StreamsBuilderFactoryBean> streamsFactory;

    public EngineHeartbeat(StringRedisTemplate redis, ObjectProvider<StreamsBuilderFactoryBean> streamsFactory) {
        this.redis = redis;
        this.streamsFactory = streamsFactory;
    }

    @Scheduled(fixedRateString = "${recon.heartbeat.interval-ms:10000}")
    public void beat() {
        try {
            StreamsBuilderFactoryBean factory = streamsFactory.getIfAvailable();
            KafkaStreams streams = factory == null ? null : factory.getKafkaStreams();
            if (streams == null || streams.state() != KafkaStreams.State.RUNNING) {
                return; // not running: let existing keys expire, that is the signal
            }
            Set<Integer> partitions = new TreeSet<>();
            for (var thread : streams.metadataForLocalThreads()) {
                for (var task : thread.activeTasks()) {
                    for (var tp : task.topicPartitions()) {
                        partitions.add(tp.partition());
                    }
                }
            }
            String now = String.valueOf(System.currentTimeMillis());
            for (int p : partitions) {
                redis.opsForValue().set(RedisKeys.heartbeat(p), now, RedisKeys.HEARTBEAT_TTL);
            }
        } catch (RuntimeException e) {
            log.warn("Heartbeat write failed (cache only): {}", e.toString());
        }
    }
}
