package com.xammer.cloud.service;

import com.xammer.cloud.dto.FinOpsReportDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class FinOpsRefreshService {

    private static final Logger logger = LoggerFactory.getLogger(FinOpsRefreshService.class);

    private final FinOpsService finOpsService;
    private final DashboardUpdateService dashboardUpdateService;
    private final CacheService cacheService;

    @Autowired
    public FinOpsRefreshService(FinOpsService finOpsService, DashboardUpdateService dashboardUpdateService, CacheService cacheService) {
        this.finOpsService = finOpsService;
        this.dashboardUpdateService = dashboardUpdateService;
        this.cacheService = cacheService;
    }

    @Async("awsTaskExecutor")
    public void triggerFinOpsReportRefresh(String accountId) {
        logger.info("Starting asynchronous FinOps report refresh for account: {}", accountId);
        try {
            // Evict the cache first to ensure fresh data
            cacheService.evictFinOpsReportCache(accountId);

            // Fetch the full FinOps report data in the background
            FinOpsReportDto freshReport = finOpsService.getFinOpsReport(accountId, true).join();

            // Send the complete report object over a specific WebSocket topic
            dashboardUpdateService.sendUpdate(accountId, freshReport);

            logger.info("Successfully completed asynchronous FinOps report refresh for account: {}", accountId);

        } catch (Exception e) {
            logger.error("Asynchronous FinOps report refresh failed for account: {}", accountId, e);
            // Optionally, send an error message over WebSocket
            dashboardUpdateService.sendUpdate(accountId, "Failed to refresh FinOps report.");
        }
    }
}