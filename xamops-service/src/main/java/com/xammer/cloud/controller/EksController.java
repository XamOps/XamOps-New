package com.xammer.cloud.controller;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.k8s.EksCompleteDashboardDto;
import com.xammer.cloud.dto.k8s.K8sNodeInfo;
import com.xammer.cloud.dto.k8s.K8sPodInfo;
import com.xammer.cloud.dto.k8s.K8sDeploymentInfo;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.EksAutomationService;
import com.xammer.cloud.service.EksDashboardService;
import com.xammer.cloud.service.EksDashboardSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/xamops/eks")
public class EksController {

    private static final Logger logger = LoggerFactory.getLogger(EksController.class);

    private final EksAutomationService eksAutomationService;
    private final CloudAccountRepository cloudAccountRepository;
    private final EksDashboardService dashboardService;
    private final EksDashboardSyncService syncService;

    public EksController(EksAutomationService eksAutomationService,
            CloudAccountRepository cloudAccountRepository,
            EksDashboardService dashboardService,
            EksDashboardSyncService syncService) {
        this.eksAutomationService = eksAutomationService;
        this.cloudAccountRepository = cloudAccountRepository;
        this.dashboardService = dashboardService;
        this.syncService = syncService;
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

        CloudAccount account = getAccount(accountId);

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
            EksCompleteDashboardDto cachedDashboard = syncService.getCachedDashboard(clusterName, accountId, region);

            if (cachedDashboard != null) {
                // Return cached data (instant load)
                LocalDateTime lastSyncedAt = syncService.getLastSyncedAt(clusterName, accountId, region);
                cachedDashboard.setLastSyncedAt(lastSyncedAt);
                logger.info("📦 Returning cached dashboard for cluster: {} (synced: {})", clusterName, lastSyncedAt);
                return ResponseEntity.ok(cachedDashboard);
            }

            // No cache exists - fetch fresh data and save to DB
            logger.info("📭 No cache found, fetching fresh data for cluster: {}", clusterName);
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
     * Always fetches fresh data from Prometheus/K8s and updates DB cache
     */
    @PostMapping("/{clusterName}/sync")
    public ResponseEntity<Map<String, Object>> syncDashboard(
            @PathVariable String clusterName,
            @RequestParam String accountId,
            @RequestParam String region) {

        try {
            CloudAccount account = getAccount(accountId);

            logger.info("🔄 Manual sync triggered for cluster: {}", clusterName);
            EksCompleteDashboardDto dashboard = syncService.syncClusterDashboard(account, clusterName, region);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Dashboard synced successfully",
                    "clusterName", clusterName,
                    "lastSyncedAt", LocalDateTime.now().toString(),
                    "totalNodes", dashboard.getClusterOverview() != null ? dashboard.getClusterOverview().getTotalNodes() : 0,
                    "totalPods", dashboard.getClusterOverview() != null ? dashboard.getClusterOverview().getTotalPods() : 0
            ));

        } catch (Exception e) {
            logger.error("❌ Error syncing dashboard for cluster: {}", clusterName, e);
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "message", "Failed to sync dashboard: " + e.getMessage()
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

    // Keep existing endpoints...
    @PostMapping("/{clusterName}/install-opencost")
    public ResponseEntity<Map<String, String>> installOpenCost(
            @PathVariable String clusterName,
            @RequestParam String accountId,
            @RequestParam String region) {

        CloudAccount account = getAccount(accountId);
        boolean success = eksAutomationService.installOpenCost(account, clusterName, region);

        if (success) {
            return ResponseEntity.ok(Map.of("message", "OpenCost installation initiated successfully."));
        } else {
            return ResponseEntity.status(500).body(Map.of("message", "Failed to initiate OpenCost installation."));
        }
    }

    @PostMapping("/{clusterName}/enable-monitoring")
    public ResponseEntity<Map<String, String>> enableMonitoring(
            @PathVariable String clusterName,
            @RequestParam String accountId,
            @RequestParam String region) {

        CloudAccount account = getAccount(accountId);
        boolean success = eksAutomationService.enableContainerInsights(account, clusterName, region);

        if (success) {
            return ResponseEntity.ok(Map.of("message", "Container Insights enabled successfully."));
        } else {
            return ResponseEntity.status(500).body(Map.of("message", "Failed to enable Container Insights."));
        }
    }

    @GetMapping("/{clusterName}/nodes")
    public ResponseEntity<List<K8sNodeInfo>> getClusterNodes(
            @PathVariable String clusterName,
            @RequestParam String accountId,
            @RequestParam String region) {

        CloudAccount account = getAccount(accountId);
        List<K8sNodeInfo> nodes = eksAutomationService.getClusterNodes(account, clusterName, region);
        return ResponseEntity.ok(nodes);
    }

    @GetMapping("/{clusterName}/pods")
    public ResponseEntity<List<K8sPodInfo>> getClusterPods(
            @PathVariable String clusterName,
            @RequestParam String accountId,
            @RequestParam String region) {

        CloudAccount account = getAccount(accountId);
        List<K8sPodInfo> pods = eksAutomationService.getClusterPods(account, clusterName, region);
        return ResponseEntity.ok(pods);
    }

    @GetMapping("/{clusterName}/deployments")
    public ResponseEntity<List<K8sDeploymentInfo>> getClusterDeployments(
            @PathVariable String clusterName,
            @RequestParam String accountId,
            @RequestParam String region) {

        CloudAccount account = getAccount(accountId);
        List<K8sDeploymentInfo> deployments = eksAutomationService.getClusterDeployments(account, clusterName, region);
        return ResponseEntity.ok(deployments);
    }

    @GetMapping("/{clusterName}/security")
    public ResponseEntity<List<Map<String, String>>> getClusterSecurityAlerts(
            @PathVariable String clusterName,
            @RequestParam String accountId,
            @RequestParam String region) {

        CloudAccount account = getAccount(accountId);
        List<Map<String, String>> alerts = eksAutomationService.getFalcoAlerts(account, clusterName, region);
        return ResponseEntity.ok(alerts);
    }
}
