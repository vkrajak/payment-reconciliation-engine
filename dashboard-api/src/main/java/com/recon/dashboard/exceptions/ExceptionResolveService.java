package com.recon.dashboard.exceptions;

import com.recon.dashboard.cache.CacheGateway;
import com.recon.dashboard.web.ConflictException;
import com.recon.dashboard.web.NotFoundException;
import com.recon.events.cache.RedisKeys;
import com.recon.events.cache.TxnStatusCodec;
import com.recon.events.cache.TxnStatusDoc;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ExceptionResolveService {

    private static final Logger log = LoggerFactory.getLogger(ExceptionResolveService.class);

    private final ExceptionJpaRepository repo;
    private final ExceptionQueryService queries;
    private final CacheGateway cache;
    private final TransactionTemplate tx;
    private final Clock clock;

    public ExceptionResolveService(ExceptionJpaRepository repo, ExceptionQueryService queries, CacheGateway cache,
                                   TransactionTemplate tx, Clock clock) {
        this.repo = repo;
        this.queries = queries;
        this.cache = cache;
        this.tx = tx;
        this.clock = clock;
    }

    /** Postgres first (committed in its own transaction), Redis second and best-effort. */
    public ExceptionDetail resolve(long id, ResolveRequest request, String headerResolvedBy) {
        String by = firstNonBlank(request.resolvedBy(), headerResolvedBy);
        if (by == null) {
            throw new IllegalArgumentException("resolvedBy is required (request body or X-Resolved-By header)");
        }
        Instant now = clock.instant();
        Integer rows = tx.execute(status -> repo.markResolved(id, now, by, request.note().trim()));
        if (rows == null || rows == 0) {
            if (repo.existsById(id)) {
                throw new ConflictException("Exception " + id + " is already RESOLVED");
            }
            throw new NotFoundException("Exception " + id + " not found");
        }
        ExceptionDetail detail = queries.detail(id);
        markCacheResolved(detail.transactionRef(), now);
        log.info("Exception {} resolved by {}", id, by);
        return detail;
    }

    /** Only touches Redis if it currently shows this txn as an open EXCEPTION (never clobber a newer MATCHED). */
    private void markCacheResolved(String transactionRef, Instant now) {
        String key = RedisKeys.txnStatus(transactionRef);
        Optional<String> current = cache.get(key);
        if (current.isEmpty()) {
            return; // lookup falls back to Postgres, which already says RESOLVED
        }
        try {
            TxnStatusDoc doc = TxnStatusCodec.fromJson(current.get());
            if (TxnStatusDoc.EXCEPTION.equals(doc.status())) {
                TxnStatusDoc updated = new TxnStatusDoc(TxnStatusDoc.RESOLVED, doc.matchedAt(), doc.sources(),
                        doc.exceptionCode(), now);
                cache.set(key, TxnStatusCodec.toJson(updated), RedisKeys.TXN_STATUS_TTL);
            }
        } catch (RuntimeException e) {
            log.warn("Could not refresh cache for {}: {}", transactionRef, e.toString());
        }
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a.trim();
        }
        return b != null && !b.isBlank() ? b.trim() : null;
    }
}
