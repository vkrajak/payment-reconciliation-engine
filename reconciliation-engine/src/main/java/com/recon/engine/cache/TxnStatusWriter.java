package com.recon.engine.cache;

import com.recon.events.cache.RedisKeys;
import com.recon.events.cache.TxnStatusCodec;
import com.recon.events.cache.TxnStatusDoc;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Collection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Write-through step of the matched path: Postgres FIRST (source of truth), Redis second.
 * Call this after the matched_transactions insert. If a Spring transaction is active the Redis write is
 * deferred to afterCommit, so Redis can never show a match that Postgres rolled back.
 * Redis failures are logged and counted, never thrown: Redis is a cache, not the durability boundary.
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

    /** matchStatus: TxnStatusDoc.MATCHED or TxnStatusDoc.MATCHED_WITH_TOLERANCE. */
    public void recordMatched(String transactionRef, String matchStatus, Instant matchedAt,
                              Collection<String> sources) {
        TxnStatusDoc doc = new TxnStatusDoc(matchStatus, matchedAt,
                sources.stream().sorted().toList(), null, matchedAt);
        runAfterCommit(() -> write(transactionRef, doc));
    }

    private void write(String transactionRef, TxnStatusDoc doc) {
        try {
            redis.opsForValue().set(RedisKeys.txnStatus(transactionRef), TxnStatusCodec.toJson(doc),
                    RedisKeys.TXN_STATUS_TTL);
        } catch (RuntimeException e) {
            meters.counter("recon.redis.write.failures", "key", "txn_status").increment();
            log.warn("Redis write failed for {} (cache only, continuing): {}", transactionRef, e.toString());
        }
    }

    private static void runAfterCommit(Runnable task) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
        } else {
            task.run();
        }
    }
}
