package com.xammer.cloud.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Hourly scheduler that drives the AWS Marketplace metering engine.
 *
 * Runs at the top of every hour (00 minutes) to:
 *   1. Aggregate all PENDING usage events across all active ISV products
 *   2. Submit them to AWS Marketplace via BatchMeterUsage (max 25 records/call)
 *   3. Store MeteringRecord with AWS response (meteringRecordId + status)
 *   4. Mark source events as SUBMITTED or FAILED
 *
 * AWS Marketplace requirement: usage records must be submitted within 1 hour
 * of the usage occurring. The scheduler truncates timestamps to the current
 * hour to satisfy this constraint.
 *
 * Scheduling can be disabled via:
 *   scheduling.metering.enabled=false
 * in application properties (e.g. for local development testing).
 */
@Component
@Slf4j
public class MeteringScheduler {

    private final MeteringEngineService meteringEngineService;

    public MeteringScheduler(MeteringEngineService meteringEngineService) {
        this.meteringEngineService = meteringEngineService;
    }

    /**
     * Runs at minute 0 of every hour (e.g. 10:00, 11:00, 12:00...).
     * cron = "0 0 * * * *" means: second=0, minute=0, any hour, any day
     */
    @Scheduled(cron = "0 0 * * * *")
    public void runMeteringCycle() {
        log.info("=== MeteringScheduler: starting hourly cycle at {} ===", LocalDateTime.now());
        try {
            Map<String, Object> result = meteringEngineService.processPendingEvents();
            log.info("=== MeteringScheduler: cycle complete — status={} ===",
                    result.getOrDefault("status", "DONE"));
        } catch (Exception e) {
            // Log but don't rethrow — scheduler should never stop due to a single cycle failure
            log.error("=== MeteringScheduler: cycle FAILED at {}: {} ===",
                    LocalDateTime.now(), e.getMessage(), e);
        }
    }

    /**
     * Catch-up run: processes any PENDING events that are older than 30 minutes
     * and may have been missed by the top-of-hour scheduler (e.g. service restart).
     * Runs at minute 30 of every hour.
     */
    @Scheduled(cron = "0 30 * * * *")
    public void runCatchUpCycle() {
        log.debug("MeteringScheduler: catch-up check at {}", LocalDateTime.now());
        try {
            meteringEngineService.processPendingEvents();
        } catch (Exception e) {
            log.error("MeteringScheduler: catch-up cycle failed: {}", e.getMessage(), e);
        }
    }
}
