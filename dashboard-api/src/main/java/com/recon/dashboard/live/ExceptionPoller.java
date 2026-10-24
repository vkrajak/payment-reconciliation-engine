package com.recon.dashboard.live;

import com.recon.dashboard.exceptions.ExceptionEntity;
import com.recon.dashboard.exceptions.ExceptionJpaRepository;
import com.recon.dashboard.exceptions.ExceptionSummary;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Exceptions are pushed from the exceptions TABLE (not the raw Kafka candidates) so the live feed shows the
 * CLASSIFIED code. Polls for ids above the last one seen. Best-effort: a row whose transaction commits late
 * with a lower id could be skipped; GET /exceptions is the authoritative list.
 */
@Component
public class ExceptionPoller {

    private static final Logger log = LoggerFactory.getLogger(ExceptionPoller.class);

    private final ExceptionJpaRepository repo;
    private final LiveEventHub hub;
    private Long lastId;   // null until first poll; first poll only records the high-water mark

    public ExceptionPoller(ExceptionJpaRepository repo, LiveEventHub hub) {
        this.repo = repo;
        this.hub = hub;
    }

    @Scheduled(fixedDelayString = "${dashboard.live.exception-poll-ms:1000}")
    void poll() {
        try {
            if (lastId == null || hub.clientCount() == 0) {
                lastId = repo.maxId();   // nobody watching: skip the backlog, don't replay it later
                return;
            }
            List<ExceptionEntity> fresh = repo.findTop200ByIdGreaterThanOrderByIdAsc(lastId);
            for (ExceptionEntity e : fresh) {
                hub.broadcast("exception", ExceptionSummary.of(e));
                lastId = e.getId();
            }
        } catch (RuntimeException ex) {
            log.warn("Exception poll failed (will retry): {}", ex.toString());
        }
    }
}
