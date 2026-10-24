package com.recon.engine.cache;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Enables @Scheduled for the heartbeat. Harmless if scheduling is already enabled elsewhere. */
@Configuration
@EnableScheduling
public class EngineCacheConfig {
}
