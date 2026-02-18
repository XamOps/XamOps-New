package com.xammer.cloud.controller.gcp;

import com.xammer.cloud.dto.gcp.GkeClusterDto;
import com.xammer.cloud.dto.gcp.GkeDashboardDto;
import com.xammer.cloud.service.gcp.GkeDashboardService;
import com.xammer.cloud.service.gcp.GkeService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/xamops/gke")
public class GkeController {

    private final GkeService gkeService;
    private final GkeDashboardService gkeDashboardService;

    public GkeController(GkeService gkeService, GkeDashboardService gkeDashboardService) {
        this.gkeService = gkeService;
        this.gkeDashboardService = gkeDashboardService;
    }

    /**
     * Returns all GKE clusters for a given GCP project.
     *
     * @param accountIds   Comma-separated GCP project ID(s); only the first is used.
     * @param forceRefresh When true, bypasses any cached data.
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
     * Returns the complete dashboard for a specific GKE cluster.
     * Node pool data is always returned (from GCP API).
     * Workload and pod metrics are returned only when the cluster has Prometheus configured.
     *
     * @param clusterName  The GKE cluster name.
     * @param accountId    The GCP project ID.
     * @param location     The cluster zone or region (e.g. "us-central1-a").
     * @param forceRefresh Reserved for future cache-busting (currently unused).
     */
    @GetMapping("/{clusterName}/dashboard")
    public ResponseEntity<GkeDashboardDto> getDashboard(
            @PathVariable String clusterName,
            @RequestParam String accountId,
            @RequestParam String location,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {
        GkeDashboardDto dashboard = gkeDashboardService.getDashboard(accountId, clusterName, location);
        return ResponseEntity.ok(dashboard);
    }

    /**
     * Triggers a manual data sync for the cluster.
     * Currently a no-op placeholder — returns 204 to unblock the frontend.
     */
    @PostMapping("/{clusterName}/sync")
    public ResponseEntity<Void> syncCluster(
            @PathVariable String clusterName,
            @RequestParam String accountId,
            @RequestParam String location) {
        return ResponseEntity.noContent().build();
    }

    /**
     * Registers (or updates) the Prometheus URL for a GKE cluster, enabling monitoring data.
     * Creates the KubernetesCluster record if it does not already exist.
     *
     * Example: POST /api/xamops/gke/my-cluster/connect
     *          ?accountId=xamops&prometheusUrl=http://1.2.3.4:9090
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
     *
     * Example: DELETE /api/xamops/gke/my-cluster/connect?accountId=xamops
     */
    @DeleteMapping("/{clusterName}/connect")
    public ResponseEntity<Void> disconnectCluster(
            @PathVariable String clusterName,
            @RequestParam String accountId) {
        gkeDashboardService.savePrometheusUrl(accountId, clusterName, null);
        return ResponseEntity.noContent().build();
    }
}
