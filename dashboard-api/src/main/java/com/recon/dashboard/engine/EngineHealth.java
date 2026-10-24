package com.recon.dashboard.engine;

import java.util.List;

/** status: UP (all expected partitions beating), DEGRADED (some missing), DOWN (none), UNKNOWN (Redis unavailable). */
public record EngineHealth(
        String status,
        int expectedPartitions,
        List<PartitionBeat> partitions,
        List<Integer> missingPartitions,
        boolean cacheAvailable) {

    public record PartitionBeat(int partition, long lastBeatEpochMs, long ageMs) { }
}
