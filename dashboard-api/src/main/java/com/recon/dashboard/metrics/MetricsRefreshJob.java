package com.recon.dashboard.metrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Pre-warms today's summary so the first page load after a TTL expiry is also a cache hit. */
@Component
public class MetricsRefreshJob {

    private static final Logger log = LoggerFactory.getLogger(MetricsRefreshJob.class);

    private final MetricsService service;

    public MetricsRefreshJob(MetricsService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${dashboard.metrics.refresh-ms:300000}",
            initialDelayString = "${dashboard.metrics.initial-delay-ms:15000}")
    public void refreshToday() {
        try {
            service.refresh(service.today());
        } catch (RuntimeException e) {
            log.warn("Metrics refresh failed (will retry next cycle): {}", e.toString());
        }
    }
}
