package com.xammer.cloud.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

@Component
public class DashboardStartupRunner implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger logger = LoggerFactory.getLogger(DashboardStartupRunner.class);

    private final DashboardDataService dashboardDataService;
    private final ProactiveCacheRefreshService proactiveCacheRefreshService;

    public DashboardStartupRunner(DashboardDataService dashboardDataService,
            ProactiveCacheRefreshService proactiveCacheRefreshService) {
        this.dashboardDataService = dashboardDataService;
        this.proactiveCacheRefreshService = proactiveCacheRefreshService;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        logger.info("🚀 [DashboardStartup] Application ready! Triggering initial dashboard data aggregation...");

        // Run in a separate thread to not block the main thread if it takes time
        new Thread(() -> {
            try {
                // Initial delay to allow other services to fully initialize if needed
                Thread.sleep(5000);
                dashboardDataService.runBackgroundAggregation();
                // Note: BillOps dashboards now have their own startup cache warming in billops module
            } catch (Exception e) {
                logger.error("❌ [DashboardStartup] Failed to run initial aggregation", e);
            }
        }).start();
    }
}
