package com.recon.classifier.job;

import com.recon.classifier.cache.TxnStatusWriter;
import com.recon.classifier.config.ClassifierProperties;
import com.recon.classifier.persistence.ExceptionRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** The "extended window sweep" from the blueprint: MISSING_SOURCE (no ledger) -> ORPHAN_EVENT after grace. */
@Component
public class OrphanSweepJob {

    private static final Logger log = LoggerFactory.getLogger(OrphanSweepJob.class);

    private final ExceptionRepository repo;
    private final ClassifierProperties props;
    private final MeterRegistry meters;
    private final TxnStatusWriter statusWriter;   // Phase 6

    public OrphanSweepJob(ExceptionRepository repo, ClassifierProperties props, MeterRegistry meters,
                          TxnStatusWriter statusWriter) {
        this.repo = repo;
        this.props = props;
        this.meters = meters;
        this.statusWriter = statusWriter;
    }

    @Scheduled(fixedDelayString = "${recon.classifier.sweep-interval-ms:60000}")
    public void sweep() {
        List<String> upgraded = repo.upgradeMissingToOrphan(props.orphanGrace());
        if (!upgraded.isEmpty()) {
            meters.counter("recon.orphan.upgrades").increment(upgraded.size());
            log.info("Upgraded {} MISSING_SOURCE exception(s) to ORPHAN_EVENT", upgraded.size());
            upgraded.forEach(statusWriter::recordOrphanUpgrade);   // after the Postgres UPDATE committed
        }
    }
}
