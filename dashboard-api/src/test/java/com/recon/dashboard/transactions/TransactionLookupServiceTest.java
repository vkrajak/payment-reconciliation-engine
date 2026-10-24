package com.recon.dashboard.transactions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.recon.dashboard.cache.CacheGateway;
import com.recon.dashboard.transactions.TransactionLookupRepository.ExceptionRow;
import com.recon.dashboard.transactions.TransactionLookupRepository.MatchedRow;
import com.recon.dashboard.web.NotFoundException;
import com.recon.events.cache.RedisKeys;
import com.recon.events.cache.TxnStatusCodec;
import com.recon.events.cache.TxnStatusDoc;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TransactionLookupServiceTest {

    private static final String REF = "TXN-1";
    private static final Instant T1 = Instant.parse("2026-10-06T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-10-06T11:00:00Z");

    private CacheGateway cache;
    private TransactionLookupRepository repo;
    private TransactionLookupService service;

    @BeforeEach
    void setUp() {
        cache = mock(CacheGateway.class);
        repo = mock(TransactionLookupRepository.class);
        service = new TransactionLookupService(cache, repo);
        when(repo.latestMatch(anyString())).thenReturn(Optional.empty());
        when(repo.latestException(anyString())).thenReturn(Optional.empty());
    }

    @Test
    void redisHitSkipsPostgres() {
        var doc = new TxnStatusDoc(TxnStatusDoc.MATCHED, T1, List.of("BANK", "LEDGER", "PSP"), null, T1);
        when(cache.get(RedisKeys.txnStatus(REF))).thenReturn(Optional.of(TxnStatusCodec.toJson(doc)));

        var r = service.lookup(REF);

        assertThat(r.servedFrom()).isEqualTo("CACHE");
        assertThat(r.status()).isEqualTo("MATCHED");
        verifyNoInteractions(repo);
    }

    @Test
    void missFallsBackToPostgresAndWritesBack() {
        when(cache.get(anyString())).thenReturn(Optional.empty());
        when(repo.latestMatch(REF)).thenReturn(Optional.of(new MatchedRow("MATCHED", T1, true, true, true)));

        var r = service.lookup(REF);

        assertThat(r.servedFrom()).isEqualTo("DATABASE");
        assertThat(r.sources()).containsExactly("BANK", "LEDGER", "PSP");
        verify(cache).set(eq(RedisKeys.txnStatus(REF)), anyString(), eq(RedisKeys.TXN_STATUS_TTL));
    }

    @Test
    void newerExceptionBeatsOlderMatch() {
        when(cache.get(anyString())).thenReturn(Optional.empty());
        when(repo.latestMatch(REF)).thenReturn(Optional.of(new MatchedRow("MATCHED", T1, true, true, true)));
        when(repo.latestException(REF)).thenReturn(
                Optional.of(new ExceptionRow("AMOUNT_MISMATCH", "OPEN", T2, T2, List.of("LEDGER", "PSP"))));

        var r = service.lookup(REF);

        assertThat(r.status()).isEqualTo("EXCEPTION");
        assertThat(r.exceptionCode()).isEqualTo("AMOUNT_MISMATCH");
    }

    @Test
    void newerMatchBeatsOlderException() {
        when(cache.get(anyString())).thenReturn(Optional.empty());
        when(repo.latestMatch(REF)).thenReturn(Optional.of(new MatchedRow("MATCHED", T2, true, true, true)));
        when(repo.latestException(REF)).thenReturn(
                Optional.of(new ExceptionRow("MISSING_SOURCE", "OPEN", T1, T1, List.of("LEDGER"))));

        assertThat(service.lookup(REF).status()).isEqualTo("MATCHED");
    }

    @Test
    void resolvedExceptionReportsResolved() {
        when(cache.get(anyString())).thenReturn(Optional.empty());
        when(repo.latestException(REF)).thenReturn(
                Optional.of(new ExceptionRow("MISSING_SOURCE", "RESOLVED", T1, T2, List.of("LEDGER"))));

        assertThat(service.lookup(REF).status()).isEqualTo("RESOLVED");
    }

    @Test
    void corruptCacheEntryFallsBackToPostgres() {
        when(cache.get(anyString())).thenReturn(Optional.of("{not json"));
        when(repo.latestMatch(REF)).thenReturn(Optional.of(new MatchedRow("MATCHED", T1, true, true, true)));

        assertThat(service.lookup(REF).servedFrom()).isEqualTo("DATABASE");
    }

    @Test
    void unknownTransactionIsNotFound() {
        when(cache.get(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.lookup("NOPE")).isInstanceOf(NotFoundException.class);
    }
}
