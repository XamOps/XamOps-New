package com.xammer.cloud.controller.gcp;

import com.xammer.cloud.dto.gcp.GkeClusterDto;
import com.xammer.cloud.dto.gcp.GkeDashboardDto;
import com.xammer.cloud.service.gcp.GkeDashboardService;
import com.xammer.cloud.service.gcp.GkeDashboardSyncService;
import com.xammer.cloud.service.gcp.GkeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/xamops/gke")
public class GkeController {

    private static final Logger logger = LoggerFactory.getLogger(GkeController.class);

    private final GkeService gkeService;
    private final GkeDashboardService gkeDashboardService;
    private final GkeDashboardSyncService gkeDashboardSyncService;

    public GkeController(GkeService gkeService,
                         GkeDashboardService gkeDashboardService,
                         GkeDashboardSyncService gkeDashboardSyncService) {
        this.gkeService = gkeService;
        this.gkeDashboardService = gkeDashboardService;
        this.gkeDashboardSyncService = gkeDashboardSyncService;
    }

    /**
     * Returns all GKE clusters for a given GCP project.
     * Cluster list is Redis-cached for 60 minutes; forceRefresh bypasses the cache.
     */
    @GetMapping("/clusters")
    public ResponseEntity<List<GkeClusterDto>> getClusters(
            @RequestParam String accountIds,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {
        String projectId = accountIds.split(",")[0].trim();
        List<GkeClusterDto> clusters = gkeService.getGkeClusters(projectId, forceRefresh);
        return ResponseEntity.ok(clusters);
    }

    /**
     * Returns the GKE cluster dashboard with 3-layer caching (Redis → DB → GCP API + Prometheus).
     *
     * Flow:
     * 1. forceRefresh=false → check DB cache; return instantly if found.
     * 2. Cache miss → fetch fresh from GCP API + Prometheus, save to DB, return.
     * 3. forceRefresh=true → skip cache, fetch fresh, save to DB, return.
     */
    @GetMapping("/{clusterName}/dashboard")
    public ResponseEntity<GkeDashboardDto> getDashboard(
            @PathVariable String clusterName,
            @RequestParam String accountId,
            @RequestParam String location,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

        logger.info("GKE dashboard request: cluster={}, project={}, location={}, forceRefresh={}",
                clusterName, accountId, location, forceRefresh);

        if (forceRefresh) {
            logger.info("Force refresh requested for GKE cluster: {}", clusterName);
            GkeDashboardDto dashboard = gkeDashboardSyncService.syncClusterDashboard(accountId, clusterName, location);
            if (dashboard != null) {
                dashboard.setLastSyncedAt(LocalDateTime.now());
            }
            return ResponseEntity.ok(dashboard);
        }

        // Try DB cache first
        GkeDashboardDto cached = gkeDashboardSyncService.getCachedDashboard(clusterName, accountId, location);
        if (cached != null) {
            LocalDateTime lastSyncedAt = gkeDashboardSyncService.getLastSyncedAt(clusterName, accountId, location);
            cached.setLastSyncedAt(lastSyncedAt);
            logger.info("Cache HIT — returning cached GKE dashboard for cluster: {} (synced: {})",
                    clusterName, lastSyncedAt);
            return ResponseEntity.ok(cached);
        }

        // Cache miss — fetch fresh and save to DB
        logger.info("Cache MISS — fetching fresh GKE dashboard for cluster: {}", clusterName);
        GkeDashboardDto dashboard = gkeDashboardSyncService.syncClusterDashboard(accountId, clusterName, location);
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
            @RequestParam String location) {
        logger.info("Manual sync requested for GKE cluster: {}", clusterName);
        gkeDashboardSyncService.syncClusterDashboard(accountId, clusterName, location);
        return ResponseEntity.noContent().build();
    }

    /**
     * Registers (or updates) the Prometheus URL for a GKE cluster, enabling monitoring data.
     */
    @PostMapping("/{clusterName}/connect")
    public ResponseEntity<Void> connectCluster(
            @PathVariable String clusterName,
            @RequestParam String accountId,
            @RequestParam String prometheusUrl) {
        gkeDashboardService.savePrometheusUrl(accountId, clusterName, prometheusUrl);
        return ResponseEntity.ok().build();
    }

    /**
     * Removes the Prometheus URL for a GKE cluster, marking it as disconnected.
     */
    @DeleteMapping("/{clusterName}/connect")
    public ResponseEntity<Void> disconnectCluster(
            @PathVariable String clusterName,
            @RequestParam String accountId) {
        gkeDashboardService.savePrometheusUrl(accountId, clusterName, null);
        return ResponseEntity.noContent().build();
    }
}
