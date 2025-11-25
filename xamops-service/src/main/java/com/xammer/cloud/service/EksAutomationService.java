package com.xammer.cloud.service;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.k8s.K8sNodeInfo;
import com.xammer.cloud.dto.k8s.K8sPodInfo;
import com.xammer.cloud.dto.k8s.K8sDeploymentInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
public class EksAutomationService {

    private static final Logger logger = LoggerFactory.getLogger(EksAutomationService.class);

    private final PrometheusService prometheusService;

    @Autowired
    public EksAutomationService(PrometheusService prometheusService) {
        this.prometheusService = prometheusService;
    }

    // ============================================================================================
    // 1. NODES (Fetched from Prometheus)
    // ============================================================================================

    public List<K8sNodeInfo> getClusterNodes(CloudAccount account, String clusterName, String region) {
        logger.info("Fetching nodes via Prometheus for cluster {}", clusterName);
        try {
            // 1. Fetch Node Inventory (Names, IPs, OS, Version)
            List<Map<String, String>> nodeList = prometheusService.getClusterNodes(clusterName);

            // 2. Fetch Metrics Asynchronously
            CompletableFuture<Map<String, Double>> cpuFuture = CompletableFuture.supplyAsync(() -> 
                prometheusService.getNodeCpuUsage(clusterName));
            
            CompletableFuture<Map<String, Double>> memFuture = CompletableFuture.supplyAsync(() -> 
                prometheusService.getNodeMemoryUsage(clusterName));

            // 3. Join Results
            Map<String, Double> cpuMap = cpuFuture.join();
            Map<String, Double> memMap = memFuture.join();

            // 4. Map to DTO
            return nodeList.stream().map(nodeData -> {
                String name = nodeData.getOrDefault("node", "Unknown");
                
                return new K8sNodeInfo(
                    name,
                    "Ready", // Assuming existence in metric implies Readiness/Up status
                    nodeData.getOrDefault("instance_type", "N/A"),
                    nodeData.getOrDefault("topology_kubernetes_io_zone", "N/A"),
                    "N/A", // Age is hard to calculate from snapshot metrics without creation timestamp
                    nodeData.getOrDefault("kubelet_version", "Unknown"),
                    formatPercentage(cpuMap.get(name)),
                    formatPercentage(memMap.get(name))
                );
            }).collect(Collectors.toList());

        } catch (Exception e) {
            logger.error("Failed to get nodes from Prometheus for cluster {}", clusterName, e);
            return Collections.emptyList();
        }
    }

    // ============================================================================================
    // 2. PODS (Fetched from Prometheus)
    // ============================================================================================

    public List<K8sPodInfo> getClusterPods(CloudAccount account, String clusterName, String region) {
        logger.info("Fetching pods via Prometheus for cluster {}", clusterName);
        try {
            // 1. Fetch Pod Inventory
            List<Map<String, String>> podList = prometheusService.getClusterPods(clusterName);

            // 2. Fetch Pod Statuses (Phase) and Restarts
            CompletableFuture<Map<String, String>> statusFuture = CompletableFuture.supplyAsync(() -> 
                prometheusService.getClusterPodStatuses(clusterName));
            
            CompletableFuture<Map<String, Double>> restartFuture = CompletableFuture.supplyAsync(() -> 
                prometheusService.getPodRestarts(clusterName));

            Map<String, String> statusMap = statusFuture.join();
            Map<String, Double> restartMap = restartFuture.join();

            // 3. Map to DTO
            return podList.stream().map(podData -> {
                String name = podData.getOrDefault("pod", "Unknown");
                String phase = statusMap.getOrDefault(name, "Unknown");
                int restarts = restartMap.getOrDefault(name, 0.0).intValue();

                return new K8sPodInfo(
                    name,
                    phase, // using Phase as "Ready" text for simplicity
                    phase, 
                    restarts,
                    "N/A", 
                    podData.getOrDefault("node", "N/A"),
                    null, 
                    null
                );
            }).collect(Collectors.toList());

        } catch (Exception e) {
            logger.error("Failed to get pods from Prometheus for cluster {}", clusterName, e);
            return Collections.emptyList();
        }
    }

    // ============================================================================================
    // 3. DEPLOYMENTS (Fetched from Prometheus)
    // ============================================================================================

    public List<K8sDeploymentInfo> getClusterDeployments(CloudAccount account, String clusterName, String region) {
        logger.info("Fetching deployments via Prometheus for cluster {}", clusterName);
        try {
            // 1. Fetch Deployment Names
            List<Map<String, String>> depList = prometheusService.getClusterDeployments(clusterName);

            // 2. Fetch Replicas info
            CompletableFuture<Map<String, Double>> availableFuture = CompletableFuture.supplyAsync(() -> 
                prometheusService.getDeploymentAvailableReplicas(clusterName));
            
            CompletableFuture<Map<String, Double>> specFuture = CompletableFuture.supplyAsync(() -> 
                prometheusService.getDeploymentSpecReplicas(clusterName));
            
            CompletableFuture<Map<String, Double>> updatedFuture = CompletableFuture.supplyAsync(() -> 
                prometheusService.getDeploymentUpdatedReplicas(clusterName));

            Map<String, Double> availableMap = availableFuture.join();
            Map<String, Double> specMap = specFuture.join();
            Map<String, Double> updatedMap = updatedFuture.join();

            // 3. Map to DTO
            return depList.stream().map(depData -> {
                String name = depData.getOrDefault("deployment", "Unknown");
                
                int available = availableMap.getOrDefault(name, 0.0).intValue();
                int desired = specMap.getOrDefault(name, 0.0).intValue();
                int updated = updatedMap.getOrDefault(name, 0.0).intValue();
                
                String readyStr = String.format("%d/%d", available, desired);

                return new K8sDeploymentInfo(
                    name,
                    readyStr,
                    updated,
                    available,
                    "N/A"
                );
            }).collect(Collectors.toList());

        } catch (Exception e) {
            logger.error("Failed to get deployments from Prometheus for cluster {}", clusterName, e);
            return Collections.emptyList();
        }
    }

    // ============================================================================================
    // UTILS & STUBS
    // ============================================================================================

    /**
     * These methods were previously used to apply manifests via KubernetesClient.
     * Since we have moved to a read-only Prometheus approach, these are stubbed
     * to avoid compilation errors in the Controller, but they perform no action.
     */
    public boolean installOpenCost(CloudAccount account, String clusterName, String region) {
        logger.info("Install OpenCost requested for {}. (Skipping: Automated installation not supported in Prometheus-only mode)", clusterName);
        return true;
    }

    public boolean enableContainerInsights(CloudAccount account, String clusterName, String region) {
        logger.info("Enable Container Insights requested for {}. (Skipping: Automated installation not supported in Prometheus-only mode)", clusterName);
        return true;
    }

    private String formatPercentage(Double value) {
        if (value == null || value.isNaN()) return "0.00 %";
        return String.format("%.2f %%", value);
    }
}