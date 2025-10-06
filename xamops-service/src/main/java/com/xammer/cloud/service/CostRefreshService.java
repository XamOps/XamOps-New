package com.xammer.cloud.service;

import com.xammer.cloud.dto.CostDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CostRefreshService {

    private static final Logger logger = LoggerFactory.getLogger(CostRefreshService.class);

    private final CostService costService;
    private final DashboardUpdateService dashboardUpdateService;

    @Autowired
    public CostRefreshService(CostService costService, DashboardUpdateService dashboardUpdateService) {
        this.costService = costService;
        this.dashboardUpdateService = dashboardUpdateService;
    }

    @Async("awsTaskExecutor")
    public void triggerCostBreakdownRefresh(String accountId, String groupBy, String tagKey) {
        logger.info("Starting async cost breakdown refresh for account: {}, groupBy: {}, tagKey: {}", accountId, groupBy, tagKey);
        try {
            List<CostDto> freshData = costService.getCostBreakdown(accountId, groupBy, tagKey, true).join();
            
            String topic = "cost-breakdown-" + groupBy + (tagKey != null ? "-" + tagKey : "");
            dashboardUpdateService.sendUpdate(accountId, freshData);

            logger.info("Successfully completed async cost breakdown refresh for account: {}", accountId);

        } catch (Exception e) {
            logger.error("Async cost breakdown refresh failed for account: {}", accountId, e);
            dashboardUpdateService.sendUpdate(accountId, "Failed to refresh cost data.");
        }
    }
}