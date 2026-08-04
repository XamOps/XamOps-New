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
     * Lease TTLs should exceed the worst-case run time, or a second instance could start
     * while the first is still working. Detections are a few pages.
     *
     * The full sync is now materially longer than when 45m was chosen: posture walks every
     * status (FAIL then PASS, roughly doubling it) and API endpoints moved to a v2 search
     * capped at 100 items per page instead of 1000. Hence 90m, and configurable — with the
     * mark-resolved guard below as the actual safety net rather than a hopeful TTL.
     */
    private static final Duration DETECTION_LEASE = Duration.ofMinutes(5);

    @org.springframework.beans.factory.annotation.Value("${upwind.sync.full-lease-minutes:90}")
    private long fullLeaseMinutes;

    @Scheduled(fixedDelayString = "${upwind.sync.detection-interval-ms:60000}",
               initialDelayString = "15000")
    public void pollDetections() {
        lease.runIfLeader("DETECTIONS", DETECTION_LEASE, syncService::syncDetections);
    }

    @Scheduled(fixedDelayString = "${upwind.sync.full-interval-ms:3600000}",
               initialDelayString = "60000")
    public void fullSync() {
        lease.runIfLeader("FULL", Duration.ofMinutes(fullLeaseMinutes), stillLeader -> {
            log.info("[Upwind] Starting full sync (vulnerabilities + posture + inventory + APIs)");
            // stillLeader is re-checked before each mark-resolved pass: if this run outlives
            // its lease, another instance may already be syncing, and two runs archiving
            // against their own start times would each wipe the other's fresh rows.
            syncService.syncAll(stillLeader);
        });
    }
}
