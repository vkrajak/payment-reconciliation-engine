package com.recon.dashboard.engine;

import com.recon.dashboard.cache.CacheGateway;
import com.recon.events.cache.RedisKeys;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class EngineHealthService {

    private final CacheGateway cache;
    private final Clock clock;
    private final int expectedPartitions;

    public EngineHealthService(CacheGateway cache, Clock dashboardClock,
                               @Value("${dashboard.engine.expected-partitions:6}") int expectedPartitions) {
        this.cache = cache;
        this.clock = dashboardClock;
        this.expectedPartitions = expectedPartitions;
    }

    public EngineHealth health() {
        Optional<List<String>> keys = cache.scanKeys(RedisKeys.HEARTBEAT_PREFIX + "*");
        if (keys.isEmpty()) {
            return new EngineHealth("UNKNOWN", expectedPartitions, List.of(), List.of(), false);
        }
        long now = clock.millis();
        List<EngineHealth.PartitionBeat> beats = new ArrayList<>();
        for (String key : keys.get()) {
            try {
                int partition = Integer.parseInt(key.substring(RedisKeys.HEARTBEAT_PREFIX.length()));
                Optional<String> value = cache.get(key);   // key may have expired between SCAN and GET
                if (value.isPresent()) {
                    long ts = Long.parseLong(value.get());
                    beats.add(new EngineHealth.PartitionBeat(partition, ts, Math.max(0, now - ts)));
                }
            } catch (NumberFormatException ignored) {
                // malformed key or value: ignore
            }
        }
        beats.sort(Comparator.comparingInt(EngineHealth.PartitionBeat::partition));
        List<Integer> missing = IntStream.range(0, expectedPartitions)
                .filter(p -> beats.stream().noneMatch(b -> b.partition() == p)).boxed().toList();
        String status = beats.isEmpty() ? "DOWN" : missing.isEmpty() ? "UP" : "DEGRADED";
        return new EngineHealth(status, expectedPartitions, beats, missing, true);
    }
}
