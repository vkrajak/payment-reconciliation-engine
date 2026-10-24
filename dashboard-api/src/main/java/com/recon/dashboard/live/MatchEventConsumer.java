package com.recon.dashboard.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Tails recon-results (every instance gets every record: unique group id, starts at latest) and pushes a
 * batched "matches" SSE event every flush interval. At 500+ events/s a browser cannot render each one,
 * so the event carries {count, dropped, sample[]} rather than one SSE message per match.
 * Payload is passed through untouched (parsed as JSON when valid), so it does not depend on the
 * recon-results schema. Nothing is buffered while no browser is connected.
 */
@Component
public class MatchEventConsumer {

    private final LiveEventHub hub;
    private final ObjectMapper mapper;
    private final int sampleSize;
    private final BlockingQueue<String> queue = new ArrayBlockingQueue<>(10_000);
    private final AtomicLong dropped = new AtomicLong();

    public MatchEventConsumer(LiveEventHub hub, ObjectMapper mapper,
                              @Value("${dashboard.live.sample-size:50}") int sampleSize) {
        this.hub = hub;
        this.mapper = mapper;
        this.sampleSize = sampleSize;
    }

    @KafkaListener(topics = "${dashboard.live.matches-topic:recon-results}",
            groupId = "#{T(java.util.UUID).randomUUID().toString()}",
            containerFactory = "liveListenerContainerFactory",
            autoStartup = "${dashboard.live.matches-enabled:true}")
    public void onMatch(ConsumerRecord<String, String> record) {
        if (hub.clientCount() == 0) {
            return;
        }
        if (!queue.offer(record.value() == null ? "null" : record.value())) {
            dropped.incrementAndGet();
        }
    }

    @Scheduled(fixedDelayString = "${dashboard.live.flush-interval-ms:500}")
    void flush() {
        List<String> batch = new ArrayList<>();
        queue.drainTo(batch);
        long droppedNow = dropped.getAndSet(0);
        if (hub.clientCount() == 0 || (batch.isEmpty() && droppedNow == 0)) {
            return;
        }
        List<Object> sample = new ArrayList<>();
        for (String raw : batch.subList(0, Math.min(sampleSize, batch.size()))) {
            sample.add(parse(raw));
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("count", batch.size());
        event.put("dropped", droppedNow);
        event.put("sample", sample);
        hub.broadcast("matches", event);
    }

    private Object parse(String raw) {
        try {
            JsonNode node = mapper.readTree(raw);
            return node == null ? raw : node;
        } catch (Exception e) {
            return raw;
        }
    }
}
