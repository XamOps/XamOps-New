package com.xammer.billops.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

import javax.annotation.PostConstruct;

@Service
public class BillopsCacheRefreshService {

    private static final Logger logger = LoggerFactory.getLogger(BillopsCacheRefreshService.class);

    private final AdminDashboardService adminDashboardService;

    public BillopsCacheRefreshService(AdminDashboardService adminDashboardService) {
        this.adminDashboardService = adminDashboardService;
    }

    @PostConstruct
    public void init() {
        logger.info("✅ BillopsCacheRefreshService initialized");
    }

    /**
     * On startup, seeds Redis from existing DB snapshot (forceRefresh=false).
     * If Redis is warm, returns immediately. If cold, rebuilds from DB snapshot.
     * Runs asynchronously so it does not delay application startup.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void warmUpCacheOnStartup() {
        logger.info("[BillOps CacheWarmUp] Application ready — warming admin dashboard cache from DB snapshot...");
        CompletableFuture.runAsync(() -> {
            try {
                adminDashboardService.getAdminDashboardSummary(false);
                logger.info("[BillOps CacheWarmUp] Admin dashboard cache warm-up complete.");
            } catch (Exception e) {
                logger.warn("[BillOps CacheWarmUp] Admin dashboard warm-up failed: {}", e.getMessage());
            }
        });
    }

    // Nightly refresh is handled by DashboardCacheScheduler (WED,SUN 2:00 AM).
    // BillopsCacheRefreshService only warms Redis from DB on startup (above).
}
