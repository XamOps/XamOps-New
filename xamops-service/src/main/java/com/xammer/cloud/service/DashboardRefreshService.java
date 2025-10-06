package com.xammer.cloud.service;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.security.ClientUserDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class DashboardRefreshService {

    private static final Logger logger = LoggerFactory.getLogger(DashboardRefreshService.class);

    private final DashboardDataService dashboardDataService;
    private final CloudAccountRepository cloudAccountRepository;
    private final CacheService cacheService;
    private final DashboardUpdateService dashboardUpdateService;

    @Autowired
    public DashboardRefreshService(
            DashboardDataService dashboardDataService,
            CloudAccountRepository cloudAccountRepository,
            CacheService cacheService,
            DashboardUpdateService dashboardUpdateService) {
        this.dashboardDataService = dashboardDataService;
        this.cloudAccountRepository = cloudAccountRepository;
        this.cacheService = cacheService;
        this.dashboardUpdateService = dashboardUpdateService;
    }

    @Async("awsTaskExecutor")
    public void triggerDashboardRefresh(String accountId, ClientUserDetails userDetails) {
        logger.info("Starting asynchronous dashboard refresh for account: {}", accountId);
        try {
            // Evict only the specific dashboard cache
            cacheService.evictDashboardCache(accountId);

            dashboardDataService.getDashboardData(accountId, true, userDetails);

            logger.info("Asynchronous dashboard refresh successfully initiated for account: {}", accountId);

        } catch (Exception e) {
            logger.error("Asynchronous dashboard refresh failed to initiate for account: {}", accountId, e);
            dashboardUpdateService.sendUpdate(accountId, "refresh-error: Failed to start dashboard data refresh.");
        }
    }
}