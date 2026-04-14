package com.xammer.cloud.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

@Component
public class DashboardStartupRunner implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger logger = LoggerFactory.getLogger(DashboardStartupRunner.class);

    private final ProactiveCacheRefreshService proactiveCacheRefreshService;

    public DashboardStartupRunner(ProactiveCacheRefreshService proactiveCacheRefreshService) {
        this.proactiveCacheRefreshService = proactiveCacheRefreshService;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        logger.info("🚀 [DashboardStartup] Application ready! Triggering initial dashboard data aggregation...");

        // Note: runBackgroundAggregation() is intentionally NOT triggered here.
        // It runs on a WED+SUN schedule only. Triggering it on every startup was
        // causing CE API calls (and ~$1/day cost) on every redeploy.
        // ProactiveCacheRefreshService.warmUpCacheOnStartup() handles startup warming
        // using forceRefresh=false (reads DB → warms Redis, no CE calls).

        // Run Azure dashboard refresh in a separate thread (billing ingestion + DB cache update)
        new Thread(() -> {
            try {
                Thread.sleep(10000); // Slightly later than AWS/GCP to avoid startup contention
                logger.info("🔷 [DashboardStartup] Triggering Azure dashboard refresh...");
                proactiveCacheRefreshService.refreshAzureDashboardData();
            } catch (Exception e) {
                logger.error("❌ [DashboardStartup] Failed to run Azure dashboard refresh", e);
            }
        }).start();
    }
}
