package com.xammer.cloud.service.upwind;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runtime hybrid cadence: detections polled frequently (default 60s), full sync
 * (vulnerabilities + posture + inventory) on a slower cadence (default 1h) that acts
 * as a safety net / reconciliation pass. When the Upwind webhook is registered it
 * pushes changes in near real-time; the full sync then just backfills anything missed
 * and marks resolved items. Both intervals are configurable (ms):
 *   upwind.sync.detection-interval-ms  (default 60000)
 *   upwind.sync.full-interval-ms       (default 3600000 = 1h)
 * Enabled only when upwind.sync.enabled=true (default false).
 */
@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(name = "upwind.sync.enabled", havingValue = "true")
public class UpwindSyncScheduler {

    private final UpwindSyncService syncService;

    @Scheduled(fixedDelayString = "${upwind.sync.detection-interval-ms:60000}",
               initialDelayString = "15000")
    public void pollDetections() {
        syncService.syncDetections();
    }

    @Scheduled(fixedDelayString = "${upwind.sync.full-interval-ms:3600000}",
               initialDelayString = "60000")
    public void fullSync() {
        log.info("[Upwind] Starting full sync (vulnerabilities + posture + inventory)");
        syncService.syncAll();
    }
}
