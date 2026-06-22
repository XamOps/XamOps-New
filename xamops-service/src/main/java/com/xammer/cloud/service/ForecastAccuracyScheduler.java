package com.xammer.cloud.service;

import com.xammer.cloud.config.SchedulingEnvironmentConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily jobs that turn raw forecast predictions into accuracy metrics:
 *  - evaluate predictions whose target day has closed (compare predicted vs actual)
 *  - prune old records to keep the table bounded
 *
 * Runs at 05:30 — after the nightly cost-data refresh jobs (2:30/3:30 AM) so the
 * actuals for yesterday are already cached.
 */
@Component
public class ForecastAccuracyScheduler {

    private static final Logger log = LoggerFactory.getLogger(ForecastAccuracyScheduler.class);

    private static final int RETENTION_DAYS = 180;

    private final ForecastAccuracyService accuracyService;
    private final SchedulingEnvironmentConfig envConfig;

    public ForecastAccuracyScheduler(ForecastAccuracyService accuracyService,
                                     SchedulingEnvironmentConfig envConfig) {
        this.accuracyService = accuracyService;
        this.envConfig = envConfig;
    }

    /** Evaluate yesterday-and-earlier pending predictions against actuals. Daily 05:30. */
    @Scheduled(cron = "0 30 5 * * *")
    public void evaluateForecastAccuracy() {
        if (!envConfig.isSchedulingEnabled()) {
            log.info("⚠️ Scheduling disabled — skipping forecast accuracy evaluation");
            return;
        }
        long start = System.currentTimeMillis();
        log.info("--- [05:30] Starting forecast accuracy evaluation ---");
        try {
            int evaluated = accuracyService.evaluatePending();
            log.info("✅ Forecast accuracy evaluation done: {} records evaluated in {} ms",
                    evaluated, System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.error("❌ Forecast accuracy evaluation failed", e);
        }
    }

    /** Prune terminal records older than the retention window. Daily 05:45. */
    @Scheduled(cron = "0 45 5 * * *")
    public void cleanupOldRecords() {
        if (!envConfig.isSchedulingEnabled()) {
            return;
        }
        try {
            accuracyService.cleanupOldRecords(RETENTION_DAYS);
        } catch (Exception e) {
            log.error("❌ Forecast accuracy cleanup failed", e);
        }
    }
}
