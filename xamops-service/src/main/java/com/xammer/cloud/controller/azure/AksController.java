package com.xammer.cloud.controller.azure;

import com.xammer.cloud.dto.azure.AksClusterDto;
import com.xammer.cloud.dto.azure.AksDashboardDto;
import com.xammer.cloud.service.azure.AksDashboardService;
import com.xammer.cloud.service.azure.AksDashboardSyncService;
import com.xammer.cloud.service.azure.AksService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/xamops/aks")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class AksController {

    private final AksService aksService;
    private final AksDashboardService aksDashboardService;
    private final AksDashboardSyncService aksDashboardSyncService;

    /**
     * Returns all AKS clusters for a given Azure subscription.
     * Cluster list is Redis-cached for 60 minutes; forceRefresh bypasses the cache.
     */
    @GetMapping("/clusters")
    public ResponseEntity<List<AksClusterDto>> getClusters(
            @RequestParam String accountIds,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {
        String subscriptionId = accountIds.split(",")[0].trim();
        log.info("Fetching AKS clusters for subscription: {}", subscriptionId);
        return ResponseEntity.ok(aksService.getClusters(subscriptionId, forceRefresh));
    }

    /**
     * Returns the AKS cluster dashboard with 3-layer caching (Redis → DB → Azure SDK + Prometheus).
     *
     * Flow:
     * 1. forceRefresh=false → check DB cache; return instantly if found.
     * 2. Cache miss → fetch fresh from Azure SDK + Prometheus, save to DB, return.
     * 3. forceRefresh=true → skip cache, fetch fresh, save to DB, return.
     */
    @GetMapping("/{clusterName}/dashboard")
    public ResponseEntity<AksDashboardDto> getDashboard(
            @PathVariable String clusterName,
            @RequestParam String accountId,
            @RequestParam String resourceGroup,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

        log.info("AKS dashboard request: cluster={}, subscription={}, resourceGroup={}, forceRefresh={}",
                clusterName, accountId, resourceGroup, forceRefresh);

        if (forceRefresh) {
            log.info("Force refresh requested for AKS cluster: {}", clusterName);
            AksDashboardDto dashboard = aksDashboardSyncService.syncClusterDashboard(accountId, clusterName, resourceGroup);
            if (dashboard != null) {
                dashboard.setLastSyncedAt(LocalDateTime.now());
            }
            return ResponseEntity.ok(dashboard);
        }

        // Try DB cache first
        AksDashboardDto cached = aksDashboardSyncService.getCachedDashboard(clusterName, accountId, resourceGroup);
        if (cached != null) {
            LocalDateTime lastSyncedAt = aksDashboardSyncService.getLastSyncedAt(clusterName, accountId, resourceGroup);
            cached.setLastSyncedAt(lastSyncedAt);
            log.info("Cache HIT — returning cached AKS dashboard for cluster: {} (synced: {})",
                    clusterName, lastSyncedAt);
            return ResponseEntity.ok(cached);
        }

        // Cache miss — fetch fresh and save to DB
        log.info("Cache MISS — fetching fresh AKS dashboard for cluster: {}", clusterName);
        AksDashboardDto dashboard = aksDashboardSyncService.syncClusterDashboard(accountId, clusterName, resourceGroup);
        if (dashboard != null) {
            dashboard.setLastSyncedAt(LocalDateTime.now());
        }
        return ResponseEntity.ok(dashboard);
    }

    /**
     * Triggers a manual data sync for the cluster (saves fresh data to DB cache).
     */
    @PostMapping("/{clusterName}/sync")
    public ResponseEntity<Void> syncCluster(
            @PathVariable String clusterName,
            @RequestParam String accountId,
            @RequestParam String resourceGroup) {
        log.info("Manual sync requested for AKS cluster: {}", clusterName);
        aksDashboardSyncService.syncClusterDashboard(accountId, clusterName, resourceGroup);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{clusterName}/connect")
    public ResponseEntity<Void> connectCluster(
            @PathVariable String clusterName,
            @RequestParam String accountId,
            @RequestParam String prometheusUrl) {
        log.info("Connecting AKS cluster {} with Prometheus URL", clusterName);
        aksService.savePrometheusUrl(accountId, clusterName, prometheusUrl);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{clusterName}/connect")
    public ResponseEntity<Void> disconnectCluster(
            @PathVariable String clusterName,
            @RequestParam String accountId) {
        log.info("Disconnecting AKS cluster {}", clusterName);
        aksService.savePrometheusUrl(accountId, clusterName, null);
        return ResponseEntity.noContent().build();
    }
}
