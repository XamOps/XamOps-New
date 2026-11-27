package com.xammer.cloud.controller.azure;

import com.xammer.cloud.dto.azure.AksClusterDto;
import com.xammer.cloud.dto.azure.AksDashboardDto;
import com.xammer.cloud.service.azure.AksDashboardService;
import com.xammer.cloud.service.azure.AksDashboardSyncService;
import com.xammer.cloud.service.azure.AksService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

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
    public ResponseEntity<?> getClusters(
            @RequestParam String accountIds,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {
        String subscriptionId = accountIds.split(",")[0].trim();
        log.info("Fetching AKS clusters for subscription: {}", subscriptionId);
        try {
            List<AksClusterDto> clusters = aksService.getClusters(subscriptionId, forceRefresh);
            return ResponseEntity.ok(clusters);
        } catch (RuntimeException e) {
            // Previously an empty 200, which the page showed as "No AKS clusters found".
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", brief(e.getMessage())));
        }
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
    public ResponseEntity<?> getDashboard(
            @PathVariable String clusterName,
            @RequestParam String accountId,
            @RequestParam String resourceGroup,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

        log.info("AKS dashboard request: cluster={}, subscription={}, resourceGroup={}, forceRefresh={}",
                clusterName, accountId, resourceGroup, forceRefresh);

        try {
            if (forceRefresh) {
                log.info("Force refresh requested for AKS cluster: {}", clusterName);
                // lastSyncedAt is already right: the build time for a fresh copy, the row's own
                // time when a failed refresh fell back to the cached copy.
                return ResponseEntity.ok(aksDashboardSyncService.syncClusterDashboard(accountId, clusterName, resourceGroup));
            }

            // Try DB cache first
            AksDashboardDto cached = aksDashboardSyncService.getCachedDashboard(clusterName, accountId, resourceGroup);
            if (cached != null) {
                LocalDateTime lastSyncedAt = aksDashboardSyncService.getLastSyncedAt(clusterName, accountId, resourceGroup);
                cached.setLastSyncedAt(lastSyncedAt);
                // The copy can be up to a day old; whether the agent is reporting is read now.
                aksDashboardService.refreshAgentLiveness(cached, accountId);
                log.info("Cache HIT — returning cached AKS dashboard for cluster: {} (synced: {})",
                        clusterName, lastSyncedAt);
                return ResponseEntity.ok(cached);
            }

            // Cache miss — fetch fresh and save to DB
            log.info("Cache MISS — fetching fresh AKS dashboard for cluster: {}", clusterName);
            return ResponseEntity.ok(aksDashboardSyncService.syncClusterDashboard(accountId, clusterName, resourceGroup));
        } catch (AksDashboardService.ClusterNotFoundException e) {
            return clusterGone(accountId, e);
        }
    }

    /**
     * Triggers a manual data sync for the cluster (saves fresh data to DB cache).
     */
    @PostMapping("/{clusterName}/sync")
    public ResponseEntity<?> syncCluster(
            @PathVariable String clusterName,
            @RequestParam String accountId,
            @RequestParam String resourceGroup) {
        log.info("Manual sync requested for AKS cluster: {}", clusterName);
        try {
            aksDashboardSyncService.syncClusterDashboard(accountId, clusterName, resourceGroup);
        } catch (AksDashboardService.ClusterNotFoundException e) {
            return clusterGone(accountId, e);
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * The cluster was deleted in Azure. Its cached dashboard is already gone; the cluster list is
     * dropped too, so going back to it does not show the cluster for another hour.
     */
    private ResponseEntity<Map<String, String>> clusterGone(String subscriptionId,
                                                            AksDashboardService.ClusterNotFoundException e) {
        log.info("{}", e.getMessage());
        try {
            aksService.evictClusters(subscriptionId);
        } catch (Exception ex) {
            log.warn("Could not evict the AKS cluster list for subscription {}: {}", subscriptionId, ex.getMessage());
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "This cluster no longer exists in Azure", "code", "CLUSTER_NOT_FOUND"));
    }

    /** Keeps an Azure SDK message (which can carry a whole JSON body) to a readable length. */
    private static String brief(String message) {
        if (message == null || message.isBlank()) {
            return "Could not list AKS clusters from Azure";
        }
        String oneLine = message.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 300 ? oneLine.substring(0, 300) + "…" : oneLine;
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
