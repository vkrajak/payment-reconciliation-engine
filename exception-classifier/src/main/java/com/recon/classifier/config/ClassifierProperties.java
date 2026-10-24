package com.recon.classifier.config;

import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "recon.classifier")
public record ClassifierProperties(
        /** Max amount delta still considered a match; must equal the engine's tolerance. */
        @DefaultValue("0.01") BigDecimal tolerance,
        /** Engine session window. */
        @DefaultValue("30m") Duration window,
        /** Extra wait after window expiry before MISSING_SOURCE (no ledger) becomes ORPHAN_EVENT. */
        @DefaultValue("30m") Duration orphanGrace) {
}
