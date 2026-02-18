package com.xammer.cloud.controller.azure;

import com.xammer.cloud.dto.azure.AksClusterDto;
import com.xammer.cloud.dto.azure.AksDashboardDto;
import com.xammer.cloud.service.azure.AksDashboardService;
import com.xammer.cloud.service.azure.AksService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/xamops/aks")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class AksController {

    private final AksService aksService;
    private final AksDashboardService aksDashboardService;

    @GetMapping("/clusters")
    public ResponseEntity<List<AksClusterDto>> getClusters(@RequestParam String accountIds) {
        String subscriptionId = accountIds.split(",")[0].trim();
        log.info("Fetching AKS clusters for subscription: {}", subscriptionId);
        return ResponseEntity.ok(aksService.getClusters(subscriptionId));
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

    @GetMapping("/{clusterName}/dashboard")
    public ResponseEntity<AksDashboardDto> getDashboard(
            @PathVariable String clusterName,
            @RequestParam String accountId,
            @RequestParam String resourceGroup,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {
        log.info("Fetching AKS dashboard for cluster {} in resource group {}", clusterName, resourceGroup);
        return ResponseEntity.ok(aksDashboardService.getDashboard(accountId, clusterName, resourceGroup));
    }
}
