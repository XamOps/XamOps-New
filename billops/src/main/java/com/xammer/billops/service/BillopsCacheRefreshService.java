package com.xammer.billops.service;

import com.xammer.billops.repository.AppUserRepository;
import com.xammer.cloud.domain.AppUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

import javax.annotation.PostConstruct;

@Service
public class BillopsCacheRefreshService {

    private static final Logger logger = LoggerFactory.getLogger(BillopsCacheRefreshService.class);

    private final AdminDashboardService adminDashboardService;
    private final UserDashboardService userDashboardService;
    private final AppUserRepository appUserRepository;

    public BillopsCacheRefreshService(AdminDashboardService adminDashboardService,
            UserDashboardService userDashboardService,
            AppUserRepository appUserRepository) {
        this.adminDashboardService = adminDashboardService;
        this.userDashboardService = userDashboardService;
        this.appUserRepository = appUserRepository;
    }

    @PostConstruct
    public void init() {
        logger.info("✅ BillopsCacheRefreshService initialized");
    }

    /**
     * Nightly refresh of BillOps dashboards at 2:00 AM.
     * This ensures that when users log in, they see the latest data immediately
     * from the 24h cache.
     */
    @Scheduled(cron = "0 0 2 * * ?")
    public void refreshBillOpsDashboards() {
        logger.info("--- [2:00 AM] Starting BillOps Dashboard Refresh ---");

        // 1. Refresh Admin Dashboard
        try {
            logger.info("Refreshing Admin Dashboard...");
            adminDashboardService.getAdminDashboardSummary(true);
            logger.info("✅ Admin Dashboard refreshed successfully.");
        } catch (Exception e) {
            logger.error("❌ Failed to refresh Admin Dashboard", e);
        }

        // 2. Refresh User Dashboards for all users
        try {
            logger.info("Refreshing User Dashboards...");
            List<AppUser> allUsers = appUserRepository.findAll();
            int successCount = 0;

            for (AppUser user : allUsers) {
                try {
                    // Only refresh for BillOps users/admins if needed, or all users
                    // For now, refreshing for all found users to be safe
                    userDashboardService.getUserDashboardSummary(user, true);
                    successCount++;
                } catch (Exception e) {
                    logger.error("❌ Failed to refresh dashboard for user: {}", user.getUsername(), e);
                }
            }
            logger.info("✅ User Dashboards refreshed: {}/{}", successCount, allUsers.size());

        } catch (Exception e) {
            logger.error("❌ Failed to fetch users for dashboard refresh", e);
        }

        logger.info("--- BillOps Dashboard Refresh Completed ---");
    }
}
