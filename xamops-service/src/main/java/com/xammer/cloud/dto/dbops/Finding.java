package com.xammer.cloud.dto.dbops;

/**
 * One actionable Pillar-A health finding (§6.4). Ranked worst-first by the service. {@code evidence}
 * carries the real number (e.g. "14.93 ms weighted avg over 1,284 calls"), never a fabricated score.
 */
public record Finding(
        String id,          // stable rule id, e.g. "unused-index:public.orders"
        String severity,    // info | warning | critical
        String metric,      // family: slowQueries | missingIndex | unusedIndex | locks | deadlocks
                            //         | connections | cacheHit | tempSpill | checkpointWal | replication
                            //         | vacuumBloat | wraparound
        String title,
        String evidence,
        String recommendation) {
}
