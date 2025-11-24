package com.xammer.cloud.controller;

import com.xammer.cloud.dto.k8s.ClusterUsageDto;
import com.xammer.cloud.dto.k8s.K8sClusterInfo;
import com.xammer.cloud.dto.k8s.K8sNodeInfo;
import com.xammer.cloud.service.EksClusterUsageService;
import com.xammer.cloud.service.EksService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/xamops/k8s")
public class CloudK8sController {

    private static final Logger logger = LoggerFactory.getLogger(CloudK8sController.class);

    private final EksService eksService;
    private final EksClusterUsageService usageService;

    public CloudK8sController(EksService eksService, EksClusterUsageService usageService) {
        this.eksService = eksService;
        this.usageService = usageService;
    }

    /**
     * Fetches aggregated usage statistics (CPU, Memory, Counts) for a cluster using Prometheus.
     * Used to populate the "Cluster Details" header in the UI.
     */
    @GetMapping("/clusters/usage")
    public CompletableFuture<ResponseEntity<ClusterUsageDto>> getClusterUsage(
        @RequestParam String accountId,
        @RequestParam String clusterName,
        @RequestParam String region) {
        
        return usageService.getClusterUsage(accountId, clusterName, region)
            .thenApply(ResponseEntity::ok)
            .exceptionally(ex -> {
                logger.error("Error fetching cluster usage for {}: {}", clusterName, ex.getMessage());
                return ResponseEntity.status(500).body(new ClusterUsageDto());
            });
    }

    @GetMapping("/clusters")
    public CompletableFuture<ResponseEntity<List<K8sClusterInfo>>> getClusters(
            @RequestParam String accountIds,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

        String accountIdToUse = accountIds.split(",")[0];

        return eksService.getEksClusterInfo(accountIdToUse, forceRefresh)
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> {
                    logger.error("Error fetching clusters: {}", ex.getMessage());
                    return ResponseEntity.status(500).body(Collections.emptyList());
                });
    }

    @GetMapping("/clusters/{clusterName}/nodes")
    public CompletableFuture<ResponseEntity<List<K8sNodeInfo>>> getNodes(
            @RequestParam String accountId,
            @PathVariable String clusterName) {
        // Note: This endpoint fetches metrics via EksService (legacy/hybrid). 
        // For the full node list with Prometheus metrics, the UI typically calls EksController.getClusterNodes
        return eksService.getK8sNodes(accountId, clusterName, false)
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> ResponseEntity.status(500).body(Collections.emptyList()));
    }

    /**
     * Fetches the details for a single cluster, using the cache.
     * This is used by eks-details.html to get its initial data (Region, Status, Version).
     */
    @GetMapping("/{clusterName}/details")
    public CompletableFuture<ResponseEntity<K8sClusterInfo>> getClusterDetails(
            @PathVariable String clusterName,
            @RequestParam String accountId) {
        
        return eksService.getEksClusterInfo(accountId, false)
            .thenApply(clusters -> clusters.stream()
                .filter(c -> c.getName().equals(clusterName))
                .findFirst()
                .map(ResponseEntity::ok) 
                .orElse(ResponseEntity.notFound().build()))
            .exceptionally(ex -> {
                logger.error("Error fetching details for cluster {}: {}", clusterName, ex.getMessage());
                return ResponseEntity.status(500).build();
            });
    }
}