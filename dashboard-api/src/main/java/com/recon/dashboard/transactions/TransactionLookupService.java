package com.recon.dashboard.transactions;

import com.recon.dashboard.cache.CacheGateway;
import com.recon.dashboard.transactions.TransactionLookupRepository.ExceptionRow;
import com.recon.dashboard.transactions.TransactionLookupRepository.MatchedRow;
import com.recon.dashboard.web.NotFoundException;
import com.recon.events.cache.RedisKeys;
import com.recon.events.cache.TxnStatusCodec;
import com.recon.events.cache.TxnStatusDoc;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Redis first, Postgres fallback (read-through: a database answer is written back to Redis). */
@Service
public class TransactionLookupService {

    private static final Logger log = LoggerFactory.getLogger(TransactionLookupService.class);

    private final CacheGateway cache;
    private final TransactionLookupRepository repo;

    public TransactionLookupService(CacheGateway cache, TransactionLookupRepository repo) {
        this.cache = cache;
        this.repo = repo;
    }

    public TransactionStatusResponse lookup(String transactionRef) {
        String key = RedisKeys.txnStatus(transactionRef);

        Optional<String> cached = cache.get(key);
        if (cached.isPresent()) {
            try {
                return toResponse(transactionRef, TxnStatusCodec.fromJson(cached.get()), "CACHE");
            } catch (RuntimeException e) {
                log.warn("Corrupt cache entry {}, falling back to Postgres", key);
            }
        }

        TxnStatusDoc doc = fromDatabase(transactionRef)
                .orElseThrow(() -> new NotFoundException("Transaction " + transactionRef + " not found"));
        cache.set(key, TxnStatusCodec.toJson(doc), RedisKeys.TXN_STATUS_TTL);
        return toResponse(transactionRef, doc, "DATABASE");
    }

    /** Whichever of {latest match, latest exception} is more recent wins (an exception can later be matched). */
    Optional<TxnStatusDoc> fromDatabase(String transactionRef) {
        Optional<MatchedRow> match = repo.latestMatch(transactionRef);
        Optional<ExceptionRow> exception = repo.latestException(transactionRef);
        if (match.isEmpty() && exception.isEmpty()) {
            return Optional.empty();
        }
        boolean matchWins = match.isPresent()
                && (exception.isEmpty() || !exception.get().detectedAt().isAfter(match.get().matchedAt()));
        if (matchWins) {
            MatchedRow m = match.get();
            List<String> sources = new ArrayList<>();
            if (m.hasBank()) sources.add("BANK");
            if (m.hasLedger()) sources.add("LEDGER");
            if (m.hasPsp()) sources.add("PSP");
            return Optional.of(new TxnStatusDoc(m.matchStatus(), m.matchedAt(), sources, null, m.matchedAt()));
        }
        ExceptionRow e = exception.get();
        String status = "RESOLVED".equals(e.status()) ? TxnStatusDoc.RESOLVED : TxnStatusDoc.EXCEPTION;
        return Optional.of(new TxnStatusDoc(status, null, e.sources(), e.code(), e.updatedAt()));
    }

    private static TransactionStatusResponse toResponse(String ref, TxnStatusDoc d, String servedFrom) {
        return new TransactionStatusResponse(ref, d.status(), d.exceptionCode(), d.matchedAt(), d.updatedAt(),
                d.sources() == null ? List.of() : d.sources(), servedFrom);
    }
}
