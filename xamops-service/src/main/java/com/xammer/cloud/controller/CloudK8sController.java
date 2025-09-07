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
@RequestMapping("/api/k8s")
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
            @RequestParam String accountIds, // <-- FIX: Changed parameter name
            @RequestParam(defaultValue = "false") boolean forceRefresh) { 
        
        String accountIdToUse = accountIds.split(",")[0]; // Use the first ID

        return eksService.getEksClusterInfo(accountIdToUse, forceRefresh)
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> ResponseEntity.status(500).body(Collections.emptyList()));
    }

    @GetMapping("/clusters/{clusterName}/nodes")
    public CompletableFuture<ResponseEntity<List<K8sNodeInfo>>> getNodes(
            @RequestParam String accountId, 
            @PathVariable String clusterName) {
        return eksService.getK8sNodes(accountId, clusterName, false)
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> ResponseEntity.status(500).body(Collections.emptyList()));
    }
}