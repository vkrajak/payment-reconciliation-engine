package com.recon.dashboard.cache;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Every Redis call in dashboard-api goes through here. Redis is a cache, never the durability boundary:
 * any failure is logged, counted (dashboard_cache_requests_total{result="error"}) and treated as a miss,
 * so callers fall back to Postgres. Redis down = slower dashboard, no data lost.
 */
@Component
public class CacheGateway {

    private static final Logger log = LoggerFactory.getLogger(CacheGateway.class);

    private final StringRedisTemplate redis;
    private final MeterRegistry meters;

    public CacheGateway(StringRedisTemplate redis, MeterRegistry meters) {
        this.redis = redis;
        this.meters = meters;
    }

    public Optional<String> get(String key) {
        try {
            String value = redis.opsForValue().get(key);
            count(value == null ? "miss" : "hit");
            return Optional.ofNullable(value);
        } catch (RuntimeException e) {
            count("error");
            log.warn("Redis GET {} failed, treating as miss: {}", key, e.toString());
            return Optional.empty();
        }
    }

    public void set(String key, String value, Duration ttl) {
        try {
            redis.opsForValue().set(key, value, ttl);
        } catch (RuntimeException e) {
            count("error");
            log.warn("Redis SET {} failed, continuing: {}", key, e.toString());
        }
    }

    /** Empty Optional = Redis unavailable (distinct from "no keys matched" = empty list). */
    public Optional<List<String>> scanKeys(String pattern) {
        try (Cursor<String> cursor = redis.scan(ScanOptions.scanOptions().match(pattern).count(100).build())) {
            List<String> keys = new ArrayList<>();
            while (cursor.hasNext()) {
                keys.add(cursor.next());
            }
            return Optional.of(keys);
        } catch (RuntimeException e) {
            count("error");
            log.warn("Redis SCAN {} failed: {}", pattern, e.toString());
            return Optional.empty();
        }
    }

    private void count(String result) {
        meters.counter("dashboard.cache.requests", "result", result).increment();
    }
}
