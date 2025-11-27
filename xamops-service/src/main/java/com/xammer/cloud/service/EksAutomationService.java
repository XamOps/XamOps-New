package com.xammer.cloud.service;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.k8s.K8sNodeInfo;
import com.xammer.cloud.dto.k8s.K8sPodInfo;
import com.xammer.cloud.dto.k8s.K8sDeploymentInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration; // Import needed
import java.time.Instant;  // Import needed
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Service
public class EksAutomationService {

    private static final Logger logger = LoggerFactory.getLogger(EksAutomationService.class);

    private final PrometheusService prometheusService;

    @Autowired
    public EksAutomationService(PrometheusService prometheusService) {
        this.prometheusService = prometheusService;
    }

    public static <T> Predicate<T> distinctByKey(Function<? super T, ?> keyExtractor) {
        Set<Object> seen = ConcurrentHashMap.newKeySet();
        return t -> seen.add(keyExtractor.apply(t));
    }

    // ============================================================================================
    // 1. NODES
    // ============================================================================================

    public List<K8sNodeInfo> getClusterNodes(CloudAccount account, String clusterName, String region) {
        logger.info("Fetching nodes via Prometheus for cluster {}", clusterName);
        try {
            // 1. Fetch Node Info (Labels)
            List<Map<String, String>> nodeList = prometheusService.getClusterNodes(clusterName);

            // 2. Async Fetch Metrics
            CompletableFuture<Map<String, Double>> cpuFuture = CompletableFuture.supplyAsync(() -> 
                prometheusService.getNodeCpuUsage(clusterName));
            
            CompletableFuture<Map<String, Double>> memFuture = CompletableFuture.supplyAsync(() -> 
                prometheusService.getNodeMemoryUsage(clusterName));

            // 3. Async Fetch Creation Timestamp for Age
            CompletableFuture<Map<String, Double>> createdFuture = CompletableFuture.supplyAsync(() -> 
                prometheusService.getNodeCreationTime(clusterName));

            Map<String, Double> cpuMap = cpuFuture.join();
            Map<String, Double> memMap = memFuture.join();
            Map<String, Double> createdMap = createdFuture.join();

            return nodeList.stream()
                .map(nodeData -> {
                    String name = nodeData.getOrDefault("node", "Unknown");
                    
                    // --- FIX 1: Fuzzy Lookup for Metrics (Fixes 0% Graphs) ---
                    // Try exact match, then try matching by removing domain (ip-1.2.3.4.internal -> ip-1.2.3.4)
                    Double cpuVal = getMetricValueFuzzy(cpuMap, name);
                    Double memVal = getMetricValueFuzzy(memMap, name);
                    
                    // --- FIX 2: Calculate Age ---
                    String age = "N/A";
                    // Try fuzzy lookup for creation time as well
                    Double createdTs = getMetricValueFuzzy(createdMap, name);
                    if (createdTs != null) {
                        age = calculateAge(createdTs);
                    }

                    return new K8sNodeInfo(
                        name,
                        "Ready", // Status is usually Ready if metric exists
                        nodeData.getOrDefault("instance_type", "N/A"),
                        nodeData.getOrDefault("topology_kubernetes_io_zone", "N/A"),
                        age,
                        nodeData.getOrDefault("kubelet_version", "Unknown"),
                        formatPercentage(cpuVal),
                        formatPercentage(memVal)
                    );
                })
                .filter(distinctByKey(K8sNodeInfo::getName))
                .collect(Collectors.toList());

        } catch (Exception e) {
            logger.error("Failed to get nodes from Prometheus for cluster {}", clusterName, e);
            return Collections.emptyList();
        }
    }

    // --- Helper for Fuzzy Matching Node Names ---
    private Double getMetricValueFuzzy(Map<String, Double> map, String nodeName) {
        if (map == null || nodeName == null) return null;
        
        // 1. Try Exact Match
        if (map.containsKey(nodeName)) return map.get(nodeName);

        // 2. Try stripping domain (e.g., ip-1.2.3.4.ec2.internal -> ip-1.2.3.4)
        if (nodeName.contains(".")) {
            String shortName = nodeName.substring(0, nodeName.indexOf("."));
            if (map.containsKey(shortName)) return map.get(shortName);
        }
        return null;
    }

    private String calculateAge(Double createdTimestamp) {
        if (createdTimestamp == null || createdTimestamp == 0) return "N/A";
        try {
            Instant created = Instant.ofEpochSecond(createdTimestamp.longValue());
            Instant now = Instant.now();
            Duration d = Duration.between(created, now);
            
            long days = d.toDays();
            if (days > 0) return days + "d";
            long hours = d.toHours();
            if (hours > 0) return hours + "h";
            return d.toMinutes() + "m";
        } catch (Exception e) {
            return "N/A";
        }
    }

    // ... (Keep existing Pods, Deployments, and Falco methods exactly as they were in your previous upload) ...
    // Note: ensure getClusterPods, getClusterDeployments, getFalcoAlerts remain in the file.

    public List<K8sPodInfo> getClusterPods(CloudAccount account, String clusterName, String region) {
        logger.info("Fetching pods via Prometheus for cluster {}", clusterName);
        try {
            List<Map<String, String>> podList = prometheusService.getClusterPods(clusterName);

            CompletableFuture<Map<String, String>> statusFuture = CompletableFuture.supplyAsync(() -> 
                prometheusService.getClusterPodStatuses(clusterName));
            
            CompletableFuture<Map<String, Double>> restartFuture = CompletableFuture.supplyAsync(() -> 
                prometheusService.getPodRestarts(clusterName));

            Map<String, String> statusMap = statusFuture.join();
            Map<String, Double> restartMap = restartFuture.join();

            return podList.stream()
                .map(podData -> {
                    String name = podData.getOrDefault("pod", "Unknown");
                    String phase = statusMap.getOrDefault(name, "Unknown");
                    int restarts = restartMap.getOrDefault(name, 0.0).intValue();

                    return new K8sPodInfo(
                        name,
                        phase,
                        phase, 
                        restarts,
                        "N/A", 
                        podData.getOrDefault("node", "N/A"),
                        null, 
                        null
                    );
                })
                .filter(distinctByKey(K8sPodInfo::getName))
                .collect(Collectors.toList());

        } catch (Exception e) {
            logger.error("Failed to get pods from Prometheus for cluster {}", clusterName, e);
            return Collections.emptyList();
        }
    }

    public List<K8sDeploymentInfo> getClusterDeployments(CloudAccount account, String clusterName, String region) {
        logger.info("Fetching deployments via Prometheus for cluster {}", clusterName);
        try {
            List<Map<String, String>> depList = prometheusService.getClusterDeployments(clusterName);

            CompletableFuture<Map<String, Double>> availableFuture = CompletableFuture.supplyAsync(() -> 
                prometheusService.getDeploymentAvailableReplicas(clusterName));
            
            CompletableFuture<Map<String, Double>> specFuture = CompletableFuture.supplyAsync(() -> 
                prometheusService.getDeploymentSpecReplicas(clusterName));
            
            CompletableFuture<Map<String, Double>> updatedFuture = CompletableFuture.supplyAsync(() -> 
                prometheusService.getDeploymentUpdatedReplicas(clusterName));

            Map<String, Double> availableMap = availableFuture.join();
            Map<String, Double> specMap = specFuture.join();
            Map<String, Double> updatedMap = updatedFuture.join();

            return depList.stream()
                .map(depData -> {
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
                })
                .filter(distinctByKey(K8sDeploymentInfo::getName))
                .collect(Collectors.toList());

        } catch (Exception e) {
            logger.error("Failed to get deployments from Prometheus for cluster {}", clusterName, e);
            return Collections.emptyList();
        }
    }

    public List<Map<String, String>> getFalcoAlerts(CloudAccount account, String clusterName, String region) {
        return prometheusService.getFalcoAlerts(clusterName);
    }

    public boolean installOpenCost(CloudAccount account, String clusterName, String region) {
        return true;
    }

    public boolean enableContainerInsights(CloudAccount account, String clusterName, String region) {
        return true;
    }

    private String formatPercentage(Double value) {
        if (value == null || value.isNaN()) return "0.00 %";
        return String.format("%.2f %%", value);
    }
}