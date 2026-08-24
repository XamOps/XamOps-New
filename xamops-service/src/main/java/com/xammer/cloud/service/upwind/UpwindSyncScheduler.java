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
 *   upwind.sync.full-interval-ms       (default 21600000 = 6h)
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
    private final UpwindOnboardingService onboardingService;

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

    /**
     * Promotes onboarding connections that Upwind has started reporting assets for.
     *
     * Runs on its own short cadence rather than inside the 6-hourly full sync: a customer who has
     * just deployed the stack is watching the Onboarding tab, and making them wait up to six
     * hours to see "Connected" would look broken. One cheap inventory search per unconnected
     * account, and nothing to do once everything is CONNECTED.
     *
     * Lease-guarded like the rest — N instances all promoting the same rows is harmless but
     * pointless load on Upwind.
     */
    @Scheduled(fixedDelayString = "${upwind.onboarding.verify-interval-ms:120000}",
               initialDelayString = "45000")
    public void verifyOnboarding() {
        lease.runIfLeader("ONBOARDING", Duration.ofMinutes(5), () -> {
            // Reconcile FIRST: it discovers accounts connected outside XamOps, which have no
            // local row at all and are therefore invisible to the pending sweep below.
            onboardingService.reconcileFromUpwind();
            onboardingService.verifyPendingConnections();
        });
    }

    // 6h, not 1h: a full run measures ~73 minutes of API time, so an hourly cadence left the
    // sync running back-to-back. fixedDelay counts from completion, so runs never overlap
    // within an instance regardless — the interval governs how hard we hammer Upwind and the DB.
    @Scheduled(fixedDelayString = "${upwind.sync.full-interval-ms:21600000}",
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
