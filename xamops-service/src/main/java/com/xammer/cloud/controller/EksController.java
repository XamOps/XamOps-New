package com.xammer.cloud.controller;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.k8s.EksCompleteDashboardDto;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.EksDashboardService;
import com.xammer.cloud.service.EksDashboardSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/xamops/eks")
@PreAuthorize("isAuthenticated()")
public class EksController {

    private static final Logger logger = LoggerFactory.getLogger(EksController.class);

    private final CloudAccountRepository cloudAccountRepository;
    private final EksDashboardService dashboardService;
    private final EksDashboardSyncService syncService;

    public EksController(CloudAccountRepository cloudAccountRepository,
            EksDashboardService dashboardService,
            EksDashboardSyncService syncService) {
        this.cloudAccountRepository = cloudAccountRepository;
        this.dashboardService = dashboardService;
        this.syncService = syncService;
    }

    private void validateAwsAccountOwnership(String awsAccountId) {
        if (com.xammer.cloud.util.SecurityUtils.isSuperAdmin()) {
            return;
        }
        com.xammer.cloud.security.ClientUserDetails principal = com.xammer.cloud.util.SecurityUtils.getPrincipal();
        Long userClientId = principal.getClientId();

        java.util.List<com.xammer.cloud.domain.CloudAccount> accounts = cloudAccountRepository.findByAwsAccountId(awsAccountId);
        if (accounts.isEmpty()) {
            throw new org.springframework.security.access.AccessDeniedException("Access denied");
        }
        boolean owns = accounts.stream().anyMatch(acc -> acc.getClient().getId().equals(userClientId));
        if (!owns) {
            throw new org.springframework.security.access.AccessDeniedException("Access denied");
        }
    }

    private CloudAccount getAccount(String accountId) {
        List<CloudAccount> accounts = cloudAccountRepository.findByAwsAccountId(accountId);
        if (accounts.isEmpty()) {
            throw new RuntimeException("Account not found: " + accountId);
        }
        return accounts.get(0);
    }

    /**
     * Get EKS Dashboard with Database Caching
     *
     * Flow:
     * 1. If forceRefresh=false: Load from database cache (instant)
     * 2. If forceRefresh=true: Fetch live data, save to DB, return fresh data
     * 3. If no cache exists: Fetch live data and save to DB
     *
     * Note: This method is synchronous to ensure @Transactional works properly
     * in the sync service (transactions don't propagate to async threads)
     */
    @GetMapping("/{clusterName}/dashboard")
    public ResponseEntity<EksCompleteDashboardDto> getCompleteDashboard(
            @PathVariable String clusterName,
            @RequestParam String accountId,
            @RequestParam String region,
            @RequestParam(required = false, defaultValue = "1.28") String version,
            @RequestParam(required = false, defaultValue = "ACTIVE") String status,
            @RequestParam(required = false, defaultValue = "false") boolean forceRefresh) {

        validateAwsAccountOwnership(accountId);
        CloudAccount account = getAccount(accountId);

        // DEBUG: Log all request parameters
        logger.info("📥 EKS Dashboard request: clusterName='{}', accountId='{}', region='{}', forceRefresh={}",
                clusterName, accountId, region, forceRefresh);

        try {
            // If forceRefresh is true, sync fresh data from Prometheus/K8s
            if (forceRefresh) {
                logger.info("🔄 Force refresh requested for cluster: {}", clusterName);
                EksCompleteDashboardDto dashboard = syncService.syncClusterDashboard(account, clusterName, region);
                if (dashboard != null) {
                    dashboard.setLastSyncedAt(LocalDateTime.now());
                }
                return ResponseEntity.ok(dashboard);
            }

            // Try to get from database cache first
            logger.info("🔍 Attempting to get cached dashboard from database with params: clusterName='{}', accountId='{}', region='{}'",
                    clusterName, accountId, region);
            EksCompleteDashboardDto cachedDashboard = syncService.getCachedDashboard(clusterName, accountId, region);

            if (cachedDashboard != null) {
                // Return cached data (instant load)
                LocalDateTime lastSyncedAt = syncService.getLastSyncedAt(clusterName, accountId, region);
                cachedDashboard.setLastSyncedAt(lastSyncedAt);
                logger.info("✅ CACHE HIT! Returning cached dashboard for cluster: {} (synced: {})", clusterName, lastSyncedAt);
                return ResponseEntity.ok(cachedDashboard);
            }

            // No cache exists - fetch fresh data and save to DB
            logger.info("❌ CACHE MISS! No cache found for cluster='{}', accountId='{}', region='{}'. Fetching fresh data from Prometheus...",
                    clusterName, accountId, region);
            EksCompleteDashboardDto dashboard = syncService.syncClusterDashboard(account, clusterName, region);
            if (dashboard != null) {
                dashboard.setLastSyncedAt(LocalDateTime.now());
            }
            return ResponseEntity.ok(dashboard);

        } catch (Exception e) {
            logger.error("❌ Error getting dashboard for cluster: {}", clusterName, e);
            return ResponseEntity.status(500).build();
        }
    }

    /**
     * Manual sync endpoint - triggered by "Sync Data" button
     * Returns cached data immediately and triggers background sync.
     * Frontend should poll /dashboard endpoint to get updated data when sync completes.
     */
    @PostMapping("/{clusterName}/sync")
    public ResponseEntity<Map<String, Object>> syncDashboard(
            @PathVariable String clusterName,
            @RequestParam String accountId,
            @RequestParam String region) {

        validateAwsAccountOwnership(accountId);
        try {
            CloudAccount account = getAccount(accountId);

            logger.info("🔄 Manual sync triggered for cluster: {} - starting background sync", clusterName);

            // Get current cached data to return immediately
            EksCompleteDashboardDto cachedDashboard = syncService.getCachedDashboard(clusterName, accountId, region);
            LocalDateTime lastSyncedAt = syncService.getLastSyncedAt(clusterName, accountId, region);

            // Trigger background sync (non-blocking)
            syncService.syncClusterDashboardAsync(account, clusterName, region);

            // Return immediately with cached data info and syncing status
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Sync started in background. Data will be updated shortly.",
                    "syncing", true,
                    "clusterName", clusterName,
                    "lastSyncedAt", lastSyncedAt != null ? lastSyncedAt.toString() : "Never",
                    "hasCachedData", cachedDashboard != null,
                    "totalNodes", cachedDashboard != null && cachedDashboard.getClusterOverview() != null
                            ? cachedDashboard.getClusterOverview().getTotalNodes() : 0,
                    "totalPods", cachedDashboard != null && cachedDashboard.getClusterOverview() != null
                            ? cachedDashboard.getClusterOverview().getTotalPods() : 0
            ));

        } catch (Exception e) {
            logger.error("❌ Error triggering sync for cluster: {}", clusterName, e);
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "syncing", false,
                    "message", "Failed to trigger sync: " + e.getMessage()
            ));
        }
    }

    /**
     * Get last sync timestamp for a cluster
     */
    @GetMapping("/{clusterName}/last-synced")
    public ResponseEntity<Map<String, Object>> getLastSynced(
            @PathVariable String clusterName,
            @RequestParam String accountId,
            @RequestParam String region) {

        validateAwsAccountOwnership(accountId);
        LocalDateTime lastSynced = syncService.getLastSyncedAt(clusterName, accountId, region);

        if (lastSynced != null) {
            return ResponseEntity.ok(Map.of(
                    "lastSyncedAt", lastSynced.toString(),
                    "cached", true
            ));
        } else {
            return ResponseEntity.ok(Map.of(
                    "cached", false
            ));
        }
    }
}
