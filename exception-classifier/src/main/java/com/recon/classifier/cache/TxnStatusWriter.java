package com.recon.classifier.cache;

import com.recon.events.cache.RedisKeys;
import com.recon.events.cache.TxnStatusCodec;
import com.recon.events.cache.TxnStatusDoc;
import com.recon.events.exception.SourceType;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Keeps txn:status in step with the exceptions table. Always called AFTER the Postgres commit
 * (the listener invokes it once ExceptionService.process has returned). Never throws:
 * a Redis problem must not trigger Kafka retries or send a good candidate to the DLT.
 */
@Component
public class TxnStatusWriter {

    private static final Logger log = LoggerFactory.getLogger(TxnStatusWriter.class);

    private final StringRedisTemplate redis;
    private final MeterRegistry meters;

    public TxnStatusWriter(StringRedisTemplate redis, MeterRegistry meters) {
        this.redis = redis;
        this.meters = meters;
    }

    public void recordException(String transactionRef, String exceptionCode, Set<SourceType> sources, Instant at) {
        try {
            TxnStatusDoc doc = new TxnStatusDoc(TxnStatusDoc.EXCEPTION, null,
                    sources.stream().map(Enum::name).sorted().toList(), exceptionCode, at);
            redis.opsForValue().set(RedisKeys.txnStatus(transactionRef), TxnStatusCodec.toJson(doc),
                    RedisKeys.TXN_STATUS_TTL);
        } catch (RuntimeException e) {
            failed(transactionRef, e);
        }
    }

    /** MISSING_SOURCE was upgraded to ORPHAN_EVENT in Postgres: reflect it, only if still an open exception. */
    public void recordOrphanUpgrade(String transactionRef) {
        try {
            String key = RedisKeys.txnStatus(transactionRef);
            String cur = redis.opsForValue().get(key);
            if (cur == null) {
                return;
            }
            TxnStatusDoc doc = TxnStatusCodec.fromJson(cur);
            if (!TxnStatusDoc.EXCEPTION.equals(doc.status())) {
                return;
            }
            TxnStatusDoc updated = new TxnStatusDoc(doc.status(), doc.matchedAt(), doc.sources(),
                    "ORPHAN_EVENT", Instant.now());
            redis.opsForValue().set(key, TxnStatusCodec.toJson(updated), RedisKeys.TXN_STATUS_TTL);
        } catch (RuntimeException e) {
            failed(transactionRef, e);
        }
    }

    private void failed(String ref, RuntimeException e) {
        meters.counter("recon.redis.write.failures", "key", "txn_status").increment();
        log.warn("Redis write failed for {} (cache only, continuing): {}", ref, e.toString());
    }
}
