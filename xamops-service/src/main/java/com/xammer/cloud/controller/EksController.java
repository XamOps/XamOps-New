package com.xammer.cloud.controller;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.k8s.EksCompleteDashboardDto;
import com.xammer.cloud.dto.k8s.K8sNodeInfo;
import com.xammer.cloud.dto.k8s.K8sPodInfo;
import com.xammer.cloud.dto.k8s.K8sDeploymentInfo;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.EksAutomationService;
import com.xammer.cloud.service.EksDashboardService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/xamops/eks")
public class EksController {

    private final EksAutomationService eksAutomationService;
    private final CloudAccountRepository cloudAccountRepository;
    private final EksDashboardService dashboardService;

    public EksController(EksAutomationService eksAutomationService,
            CloudAccountRepository cloudAccountRepository,
            EksDashboardService dashboardService) {
        this.eksAutomationService = eksAutomationService;
        this.cloudAccountRepository = cloudAccountRepository;
        this.dashboardService = dashboardService;
    }

    private CloudAccount getAccount(String accountId) {
        List<CloudAccount> accounts = cloudAccountRepository.findByAwsAccountId(accountId);
        if (accounts.isEmpty()) {
            throw new RuntimeException("Account not found: " + accountId);
        }
        return accounts.get(0);
    }

    // ✅ NEW: Unified Dashboard Endpoint
    @GetMapping("/{clusterName}/dashboard")
    public CompletableFuture<ResponseEntity<EksCompleteDashboardDto>> getCompleteDashboard(
            @PathVariable String clusterName,
            @RequestParam String accountId,
            @RequestParam String region,
            @RequestParam(required = false, defaultValue = "1.28") String version,
            @RequestParam(required = false, defaultValue = "ACTIVE") String status) {

        CloudAccount account = getAccount(accountId);

        return dashboardService.getCompleteDashboard(account, clusterName, region, version, status)
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> {
                    ex.printStackTrace();
                    return ResponseEntity.status(500).build();
                });
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