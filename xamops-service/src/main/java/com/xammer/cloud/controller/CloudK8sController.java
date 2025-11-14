package com.xammer.cloud.controller;

import com.xammer.cloud.dto.k8s.ClusterUsageDto;
import com.xammer.cloud.dto.k8s.K8sClusterInfo;
import com.xammer.cloud.dto.k8s.K8sNodeInfo;
import com.xammer.cloud.service.EksClusterUsageService;
import com.xammer.cloud.service.EksService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/xamops/k8s")
public class CloudK8sController {

    private final EksService eksService;
    private final EksClusterUsageService usageService;

    public CloudK8sController(EksService eksService, EksClusterUsageService usageService) {
        this.eksService = eksService;
        this.usageService = usageService;
    }

    @PostMapping("/clusters/usage")
    public CompletableFuture<ResponseEntity<ClusterUsageDto>> getClusterUsage(
        @RequestParam String accountId,
        @RequestParam String clusterName,
        @RequestParam String region) {
        return usageService.getClusterUsage(accountId, clusterName, region)
            .thenApply(ResponseEntity::ok)
            .exceptionally(ex -> ResponseEntity.status(500).body(new ClusterUsageDto()));
    }

    @GetMapping("/clusters")
    public CompletableFuture<ResponseEntity<List<K8sClusterInfo>>> getClusters(
            @RequestParam String accountIds,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {

        String accountIdToUse = accountIds.split(",")[0];

        return eksService.getEksClusterInfo(accountIdToUse, forceRefresh)
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> ResponseEntity.status(500).body(Collections.emptyList()));
    }

    @GetMapping("/clusters/{clusterName}/nodes")
    public CompletableFuture<ResponseEntity<List<K8sNodeInfo>>> getNodes(
            @RequestParam String accountId,
            @PathVariable String clusterName) {
        // Note: This endpoint from EksService only gets metrics, not K8s API nodes.
        // We are leaving it as-is and using EksController for the K8s API data.
        return eksService.getK8sNodes(accountId, clusterName, false)
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> ResponseEntity.status(500).body(Collections.emptyList()));
    }

    // --- NEW ENDPOINT TO FIX THE UI ERROR ---
    /**
     * Fetches the details for a single cluster, using the cache.
     * This is used by eks-details.html to get its initial data.
     */
    @GetMapping("/{clusterName}/details")
    public CompletableFuture<ResponseEntity<K8sClusterInfo>> getClusterDetails(
            @PathVariable String clusterName,
            @RequestParam String accountId) {
        
        // Call the existing service method (it uses the cache, per your logs)
        return eksService.getEksClusterInfo(accountId, false)
            .thenApply(clusters -> clusters.stream()
                .filter(c -> c.getName().equals(clusterName))
                .findFirst()
                .map(ResponseEntity::ok) // Found it, return 200 OK with the cluster
                .orElse(ResponseEntity.notFound().build())); // Not found, return 404
    }
}