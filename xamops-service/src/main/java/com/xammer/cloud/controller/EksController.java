package com.xammer.cloud.controller;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.EksAutomationService;

// --- NEW IMPORTS ---
// Import the DTOs you will be returning.
// (Ensure the package path is correct for your project)
import com.xammer.cloud.dto.k8s.K8sNodeInfo;
import com.xammer.cloud.dto.k8s.K8sPodInfo;
import com.xammer.cloud.dto.k8s.K8sDeploymentInfo;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/xamops/eks")
public class EksController {

    private final EksAutomationService eksAutomationService;
    private final CloudAccountRepository cloudAccountRepository;

    public EksController(EksAutomationService eksAutomationService, CloudAccountRepository cloudAccountRepository) {
        this.eksAutomationService = eksAutomationService;
        this.cloudAccountRepository = cloudAccountRepository;
    }

    /**
     * Helper method to find a single account.
     */
    private CloudAccount getAccount(String accountId) {
        List<CloudAccount> accounts = cloudAccountRepository.findByAwsAccountId(accountId);
        if (accounts.isEmpty()) {
            throw new RuntimeException("Account not found: " + accountId);
        }
        return accounts.get(0); // Use the first account
    }

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
            return ResponseEntity.ok(Map.of("message", "Container Insights enabled successfully. Metrics will appear shortly."));
        } else {
            return ResponseEntity.status(500).body(Map.of("message", "Failed to enable Container Insights. Check server logs."));
        }
    }

    // --- NEW ENDPOINT FOR NODES ---
    @GetMapping("/{clusterName}/nodes")
    public ResponseEntity<List<K8sNodeInfo>> getClusterNodes(
            @PathVariable String clusterName,
            @RequestParam String accountId,
            @RequestParam String region) {
        
        CloudAccount account = getAccount(accountId);
        
        // NOTE: You must create this 'getClusterNodes' method in your EksAutomationService
        List<K8sNodeInfo> nodes = eksAutomationService.getClusterNodes(account, clusterName, region);
        
        return ResponseEntity.ok(nodes);
    }

    // --- NEW ENDPOINT FOR PODS ---
    @GetMapping("/{clusterName}/pods")
    public ResponseEntity<List<K8sPodInfo>> getClusterPods(
            @PathVariable String clusterName,
            @RequestParam String accountId,
            @RequestParam String region) {
        
        CloudAccount account = getAccount(accountId);
        
        // NOTE: You must create this 'getClusterPods' method in your EksAutomationService
        List<K8sPodInfo> pods = eksAutomationService.getClusterPods(account, clusterName, region);
        
        return ResponseEntity.ok(pods);
    }

    // --- NEW ENDPOINT FOR DEPLOYMENTS ---
    @GetMapping("/{clusterName}/deployments")
    public ResponseEntity<List<K8sDeploymentInfo>> getClusterDeployments(
            @PathVariable String clusterName,
            @RequestParam String accountId,
            @RequestParam String region) {
        
        CloudAccount account = getAccount(accountId);

        // NOTE: You must create this 'getClusterDeployments' method in your EksAutomationService
        List<K8sDeploymentInfo> deployments = eksAutomationService.getClusterDeployments(account, clusterName, region);
        
        return ResponseEntity.ok(deployments);
    }
}