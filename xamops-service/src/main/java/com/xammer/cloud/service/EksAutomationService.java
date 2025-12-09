package com.xammer.cloud.service;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.KubernetesCluster;
import com.xammer.cloud.dto.k8s.K8sNodeInfo;
import com.xammer.cloud.dto.k8s.K8sPodInfo;
import com.xammer.cloud.dto.k8s.K8sDeploymentInfo;
import com.xammer.cloud.repository.KubernetesClusterRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
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
    private final KubernetesClusterRepository kubernetesClusterRepository;

    @Autowired
    public EksAutomationService(PrometheusService prometheusService, 
                                KubernetesClusterRepository kubernetesClusterRepository) {
        this.prometheusService = prometheusService;
        this.kubernetesClusterRepository = kubernetesClusterRepository;
    }

    public static <T> Predicate<T> distinctByKey(Function<? super T, ?> keyExtractor) {
        Set<Object> seen = ConcurrentHashMap.newKeySet();
        return t -> seen.add(keyExtractor.apply(t));
    }

    private String getPrometheusUrl(CloudAccount account, String clusterName) {
        return kubernetesClusterRepository.findByCloudAccountAndClusterName(account, clusterName)
                .map(KubernetesCluster::getPrometheusUrl)
                .orElseThrow(() -> new RuntimeException("Prometheus URL not found for cluster: " + clusterName));
    }

    // ============================================================================================
    // 1. NODES
    // ============================================================================================

    public List<K8sNodeInfo> getClusterNodes(CloudAccount account, String clusterName, String region) {
        logger.info("Fetching nodes via Prometheus for cluster {}", clusterName);
        try {
            String promUrl = getPrometheusUrl(account, clusterName);

            // 1. Fetch Node Info from kube_node_info
            List<Map<String, String>> nodeList = prometheusService.getClusterNodes(promUrl, clusterName);
            logger.info("📊 Found {} nodes from kube_node_info", nodeList.size());

            // 2. Async Fetch Metrics (now using 'hostname' label)
            CompletableFuture<Map<String, Double>> cpuFuture = CompletableFuture.supplyAsync(() -> 
                prometheusService.getNodeCpuUsage(promUrl, clusterName));
            
            CompletableFuture<Map<String, Double>> memFuture = CompletableFuture.supplyAsync(() -> 
                prometheusService.getNodeMemoryUsage(promUrl, clusterName));

            CompletableFuture<Map<String, Double>> createdFuture = CompletableFuture.supplyAsync(() -> 
                prometheusService.getNodeCreationTime(promUrl, clusterName));

            Map<String, Double> cpuMap = cpuFuture.join();
            Map<String, Double> memMap = memFuture.join();
            Map<String, Double> createdMap = createdFuture.join();

            logger.info("✅ Node Metrics Retrieved: CPU={}, MEM={}, CREATED={}", 
                cpuMap.size(), memMap.size(), createdMap.size());

            // 3. Match node names with metrics
            // cpuMap and memMap use 'hostname' which should match 'node' from kube_node_info
            return nodeList.stream()
                .map(nodeData -> {
                    String nodeName = nodeData.getOrDefault("node", "Unknown");
                    
                    // Direct lookup - hostname from metrics should match node name
                    Double cpuVal = cpuMap.get(nodeName);
                    Double memVal = memMap.get(nodeName);
                    
                    if (cpuVal == null || memVal == null) {
                        logger.warn("⚠️ Missing metrics for node: {}. Available keys in cpuMap: {}", 
                            nodeName, cpuMap.keySet());
                    } else {
                        logger.debug("✅ Matched node: {} -> CPU={:.2f}%, MEM={:.2f}%", 
                            nodeName, cpuVal, memVal);
                    }
                    
                    // Calculate age
                    String age = "N/A";
                    Double createdTs = createdMap.get(nodeName);
                    if (createdTs != null) {
                        age = calculateAge(createdTs);
                    }

                    String zone = nodeData.getOrDefault("topology_kubernetes_io_zone", "N/A");
                    String instanceType = nodeData.getOrDefault("instance_type", "t3.small");
                    String k8sVersion = nodeData.getOrDefault("kubelet_version", "Unknown");

                    return new K8sNodeInfo(
                        nodeName,
                        "Ready",
                        instanceType,
                        zone,
                        age,
                        k8sVersion,
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

    // ============================================================================================
    // 2. PODS
    // ============================================================================================

    public List<K8sPodInfo> getClusterPods(CloudAccount account, String clusterName, String region) {
        logger.info("Fetching pods via Prometheus for cluster {}", clusterName);
        try {
            String promUrl = getPrometheusUrl(account, clusterName);

            List<Map<String, String>> podList = prometheusService.getClusterPods(promUrl, clusterName);

            CompletableFuture<Map<String, String>> statusFuture = CompletableFuture.supplyAsync(() -> 
                prometheusService.getClusterPodStatuses(promUrl, clusterName));
            
            CompletableFuture<Map<String, Double>> restartFuture = CompletableFuture.supplyAsync(() -> 
                prometheusService.getPodRestarts(promUrl, clusterName));
            
            // ✅ NEW: Fetch pod creation times
            CompletableFuture<Map<String, Double>> createdFuture = CompletableFuture.supplyAsync(() -> 
                prometheusService.getPodCreationTime(promUrl, clusterName));

            Map<String, String> statusMap = statusFuture.join();
            Map<String, Double> restartMap = restartFuture.join();
            Map<String, Double> createdMap = createdFuture.join();

            return podList.stream()
                .map(podData -> {
                    String name = podData.getOrDefault("pod", "Unknown");
                    String phase = statusMap.getOrDefault(name, "Unknown");
                    int restarts = restartMap.getOrDefault(name, 0.0).intValue();

                    // ✅ NEW: Calculate age
                    String age = "N/A";
                    Double createdTs = createdMap.get(name);
                    if (createdTs != null) {
                        age = calculateAge(createdTs);
                    }

                    return new K8sPodInfo(
                        name,
                        phase,
                        phase, 
                        restarts,
                        age,
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

    // ============================================================================================
    // 3. DEPLOYMENTS
    // ============================================================================================

    public List<K8sDeploymentInfo> getClusterDeployments(CloudAccount account, String clusterName, String region) {
        logger.info("Fetching deployments via Prometheus for cluster {}", clusterName);
        try {
            String promUrl = getPrometheusUrl(account, clusterName);

            List<Map<String, String>> depList = prometheusService.getClusterDeployments(promUrl, clusterName);

            CompletableFuture<Map<String, Double>> availableFuture = CompletableFuture.supplyAsync(() -> 
                prometheusService.getDeploymentAvailableReplicas(promUrl, clusterName));
            
            CompletableFuture<Map<String, Double>> specFuture = CompletableFuture.supplyAsync(() -> 
                prometheusService.getDeploymentSpecReplicas(promUrl, clusterName));
            
            CompletableFuture<Map<String, Double>> updatedFuture = CompletableFuture.supplyAsync(() -> 
                prometheusService.getDeploymentUpdatedReplicas(promUrl, clusterName));
            
            // ✅ NEW: Fetch deployment creation times
            CompletableFuture<Map<String, Double>> createdFuture = CompletableFuture.supplyAsync(() -> 
                prometheusService.getDeploymentCreationTime(promUrl, clusterName));

            Map<String, Double> availableMap = availableFuture.join();
            Map<String, Double> specMap = specFuture.join();
            Map<String, Double> updatedMap = updatedFuture.join();
            Map<String, Double> createdMap = createdFuture.join();

            return depList.stream()
                .map(depData -> {
                    String name = depData.getOrDefault("deployment", "Unknown");
                    
                    int available = availableMap.getOrDefault(name, 0.0).intValue();
                    int desired = specMap.getOrDefault(name, 0.0).intValue();
                    int updated = updatedMap.getOrDefault(name, 0.0).intValue();
                    
                    String readyStr = String.format("%d/%d", available, desired);

                    // ✅ NEW: Calculate age
                    String age = "N/A";
                    Double createdTs = createdMap.get(name);
                    if (createdTs != null) {
                        age = calculateAge(createdTs);
                    }

                    return new K8sDeploymentInfo(
                        name,
                        readyStr,
                        updated,
                        available,
                        age
                    );
                })
                .filter(distinctByKey(K8sDeploymentInfo::getName))
                .collect(Collectors.toList());

        } catch (Exception e) {
            logger.error("Failed to get deployments from Prometheus for cluster {}", clusterName, e);
            return Collections.emptyList();
        }
    }

    // ============================================================================================
    // 4. SECURITY (FALCO)
    // ============================================================================================

    public List<Map<String, String>> getFalcoAlerts(CloudAccount account, String clusterName, String region) {
        try {
            String promUrl = getPrometheusUrl(account, clusterName);
            return prometheusService.getFalcoAlerts(promUrl, clusterName);
        } catch (Exception e) {
            logger.error("Failed to fetch Falco alerts for cluster {}", clusterName, e);
            return Collections.emptyList();
        }
    }

    // ============================================================================================
    // HELPER METHODS
    // ============================================================================================

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
            logger.error("Error calculating age from timestamp {}", createdTimestamp, e);
            return "N/A";
        }
    }

    private String formatPercentage(Double value) {
        if (value == null || value.isNaN()) return "0.00 %";
        return String.format("%.2f %%", value);
    }

    public boolean installOpenCost(CloudAccount account, String clusterName, String region) {
        return true;
    }

    public boolean enableContainerInsights(CloudAccount account, String clusterName, String region) {
        return true;
    }
}