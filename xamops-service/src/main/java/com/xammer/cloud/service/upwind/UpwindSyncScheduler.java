package com.xammer.cloud.service.upwind;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runtime hybrid cadence (see spec): detections every 60s, full sync every 4h.
 * Enabled only when upwind.sync.enabled=true (default false).
 */
@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(name = "upwind.sync.enabled", havingValue = "true")
public class UpwindSyncScheduler {

    private final UpwindSyncService syncService;

    @Scheduled(fixedDelayString = "60000", initialDelayString = "15000")
    public void pollDetections() {
        syncService.syncDetections();
    }

    @Scheduled(fixedDelayString = "14400000", initialDelayString = "60000")
    public void fullSync() {
        log.info("[Upwind] Starting full sync (vulnerabilities + posture)");
        syncService.syncAll();
    }
}
