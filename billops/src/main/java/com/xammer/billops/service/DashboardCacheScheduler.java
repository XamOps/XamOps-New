package com.xammer.billops.service;

import com.xammer.billops.repository.AppUserRepository;
import com.xammer.cloud.domain.AppUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DashboardCacheScheduler {

    private static final Logger logger = LoggerFactory.getLogger(DashboardCacheScheduler.class);

    private final AdminDashboardService adminDashboardService;
    private final UserDashboardService userDashboardService;
    private final AppUserRepository userRepository;

    public DashboardCacheScheduler(AdminDashboardService adminDashboardService,
                                  UserDashboardService userDashboardService,
                                  AppUserRepository userRepository) {
        this.adminDashboardService = adminDashboardService;
        this.userDashboardService = userDashboardService;
        this.userRepository = userRepository;
    }

    /**
     * Refresh dashboard caches on Wednesday and Sunday nights so users
     * see updated data on Monday and Thursday. Use the refresh button for live data.
     */
    @Scheduled(cron = "0 0 2 * * WED,SUN")
    public void refreshCachesNightly() {
        logger.info("Starting scheduled nightly dashboard cache refresh...");
        try {
            refreshAllDashboardCaches();
            logger.info("Nightly dashboard cache refresh completed successfully");
        } catch (Exception e) {
            logger.error("Error during nightly cache refresh", e);
        }
    }

    /**
     * Refresh all dashboard caches
     */
    private void refreshAllDashboardCaches() {
        // Refresh admin dashboard cache
        logger.info("Refreshing admin dashboard cache...");
        adminDashboardService.getAdminDashboardSummary(true);

        // Refresh user dashboard caches for all billops users
        logger.info("Refreshing user dashboard caches...");
        List<AppUser> billopsUsers = userRepository.findAll().stream()
                .filter(user -> user.getRole() != null &&
                        (user.getRole().equals("ROLE_BILLOPS") || user.getRole().equals("ROLE_BILLOPS_ADMIN")))
                .toList();

        int refreshedCount = 0;
        for (AppUser user : billopsUsers) {
            try {
                userDashboardService.getUserDashboardSummary(user, true);
                refreshedCount++;
            } catch (Exception e) {
                logger.warn("Failed to refresh cache for user: {}", user.getUsername(), e);
            }
        }

        logger.info("Refreshed dashboard caches for {} users", refreshedCount);
    }
}
