package com.xammer.cloud.service.upwind;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Runtime hybrid cadence: detections polled frequently (default 60s), full sync
 * (vulnerabilities + posture + inventory + API endpoints) on a slower cadence (default 1h)
 * that acts as a safety net / reconciliation pass. When the Upwind webhook is registered it
 * pushes changes in near real-time; the full sync then just backfills anything missed
 * and marks resolved items. Both intervals are configurable (ms):
 *   upwind.sync.detection-interval-ms  (default 60000)
 *   upwind.sync.full-interval-ms       (default 3600000 = 1h)
 * Enabled only when upwind.sync.enabled=true.
 *
 * Every run is lease-guarded. live.xamops.com is an ASG, so all instances boot this same
 * scheduler; the lease keeps exactly one of them syncing instead of N duplicating the work.
 */
@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(name = "upwind.sync.enabled", havingValue = "true")
public class UpwindSyncScheduler {

    private final UpwindSyncService syncService;
    private final UpwindSyncLease lease;

    /**
     * Lease TTLs must exceed the worst-case run time, or a second instance could start
     * while the first is still working. Detections are a few pages; the full sync walks
     * ~20 pages per domain plus inventory.
     */
    private static final Duration DETECTION_LEASE = Duration.ofMinutes(5);
    private static final Duration FULL_LEASE = Duration.ofMinutes(45);

    @Scheduled(fixedDelayString = "${upwind.sync.detection-interval-ms:60000}",
               initialDelayString = "15000")
    public void pollDetections() {
        lease.runIfLeader("DETECTIONS", DETECTION_LEASE, syncService::syncDetections);
    }

    @Scheduled(fixedDelayString = "${upwind.sync.full-interval-ms:3600000}",
               initialDelayString = "60000")
    public void fullSync() {
        lease.runIfLeader("FULL", FULL_LEASE, () -> {
            log.info("[Upwind] Starting full sync (vulnerabilities + posture + inventory + APIs)");
            syncService.syncAll();
        });
    }
}
