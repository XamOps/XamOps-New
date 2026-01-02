package com.xammer.cloud.service;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.KubernetesCluster;
import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.dto.k8s.EksCompleteDashboardDto;
import com.xammer.cloud.dto.k8s.EksCompleteDashboardDto.*;
import com.xammer.cloud.dto.k8s.KubescapeDashboard;
import com.xammer.cloud.dto.k8s.TrivyDashboard;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import com.xammer.cloud.repository.KubernetesClusterRepository;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
public class EksDashboardService {

    private static final Logger logger = LoggerFactory.getLogger(EksDashboardService.class);

    private final PrometheusService prometheusService;
    private final KubernetesClusterRepository kubernetesClusterRepository;
    private final KubernetesClusterConfigRepository clusterConfigRepository;
    private final KubescapeService kubescapeService;
    private final TrivyService trivyService;
    private final K8sClientFactory k8sClientFactory;

    public EksDashboardService(PrometheusService prometheusService,
            KubernetesClusterRepository kubernetesClusterRepository,
            KubernetesClusterConfigRepository clusterConfigRepository,
            KubescapeService kubescapeService,
            TrivyService trivyService,
            K8sClientFactory k8sClientFactory) {
        this.prometheusService = prometheusService;
        this.kubernetesClusterRepository = kubernetesClusterRepository;
        this.clusterConfigRepository = clusterConfigRepository;
        this.kubescapeService = kubescapeService;
        this.trivyService = trivyService;
        this.k8sClientFactory = k8sClientFactory;
    }

    private String getPrometheusUrl(CloudAccount account, String clusterName) {
        // ✅ NEW CODE: Use Account ID (Long) to avoid ClassLoader/Object mismatch issues
        // Using findByCloudAccount_IdAndClusterName for explicit JPA property traversal
        return kubernetesClusterRepository.findByCloudAccount_IdAndClusterName(account.getId(), clusterName)
                .map(KubernetesCluster::getPrometheusUrl)
                .orElseGet(() -> {
                    logger.warn("⚠️ Cluster '{}' not found for Account ID: {}. Checking available clusters...",
                            clusterName, account.getId());
                    // This helps debug name mismatches
                    kubernetesClusterRepository.findByCloudAccountId(account.getId())
                            .forEach(c -> logger.warn("👉 Found Cluster: '{}'", c.getClusterName()));
                    return null;
                });
    }

    public CompletableFuture<EksCompleteDashboardDto> getCompleteDashboard(
            CloudAccount account, String clusterName, String region, String version, String status) {

        return CompletableFuture.supplyAsync(() -> {
            try {
                String promUrl = getPrometheusUrl(account, clusterName);

                if (promUrl == null) {
                    logger.warn("⚠️ No Prometheus URL found for cluster: {}. Returning empty dashboard.", clusterName);
                    return createEmptyDashboard(clusterName, region, version, status);
                }

                logger.info("🎯 Fetching complete dashboard data for cluster: {}", clusterName);

                EksCompleteDashboardDto dashboard = new EksCompleteDashboardDto();

                // Build sections
                dashboard.setClusterOverview(buildClusterOverview(promUrl, clusterName, region, version, status));
                dashboard.setCostMetrics(buildCostMetrics(promUrl, clusterName));
                dashboard.setSecurityMetrics(buildSecurityMetrics(promUrl, clusterName));
                dashboard.setResourceUtilization(buildResourceUtilization(promUrl, clusterName));
                dashboard.setWorkloadHealth(buildWorkloadHealth(promUrl, clusterName));

                // Fetch Kubescape data if enabled
                try {
                    String awsAccountIdStr = account.getAwsAccountId();
                    Long providerId = null;
                    if (awsAccountIdStr != null && awsAccountIdStr.matches("\\d+")) {
                        providerId = Long.valueOf(awsAccountIdStr);
                    }

                    logger.info("🔍 Looking up Kubescape config for cluster: {}. DB ID: {}, AWS ID: {}",
                            clusterName, account.getId(), providerId);

                    // Step 1: Fix Account ID Resolution - Prioritize AWS Account Number
                    Optional<KubernetesClusterConfig> configOpt = providerId != null
                            ? clusterConfigRepository.findByCloudAccountIdAndClusterName(providerId, clusterName)
                            : Optional.empty();

                    if (configOpt.isEmpty()) {
                        logger.warn("⚠️ Cluster config NOT found using AWS ID, trying DB ID FALLBACK: {}",
                                account.getId());
                        configOpt = clusterConfigRepository.findByCloudAccountIdAndClusterName(account.getId(),
                                clusterName);
                    }

                    if (configOpt.isPresent()) {
                        KubernetesClusterConfig config = configOpt.get();
                        if (Boolean.TRUE.equals(config.getKubescapeEnabled())) {
                            String kubeconfig = config.getKubeconfigYaml();
                            if (kubeconfig != null && !kubeconfig.isEmpty()) {
                                // Step 3: Fix Resource Cleanup (Try-With-Resources)
                                try (KubernetesClient client = k8sClientFactory.createFromKubeconfig(kubeconfig)) {
                                    KubescapeDashboard kDashboard = kubescapeService.fetchDashboardData(
                                            client,
                                            clusterName,
                                            awsAccountIdStr != null ? awsAccountIdStr
                                                    : String.valueOf(account.getId()));
                                    dashboard.setKubescape(kDashboard);

                                    // NEW: Fetch Trivy data
                                    TrivyDashboard tDashboard = trivyService.fetchDashboardData(client);
                                    dashboard.setTrivy(tDashboard);

                                    logger.info("✅ Kubescape & Trivy data integrated for cluster: {}", clusterName);
                                }
                            } else {
                                logger.warn("⚠️ Kubeconfig is EMPTY for cluster: {}", clusterName);
                            }
                        } else {
                            logger.info("ℹ️ Kubescape is NOT enabled for cluster: {}", clusterName);
                        }
                    } else {
                        logger.warn("⚠️ No cluster config found for cluster: {}. All ID lookups failed.", clusterName);
                    }
                } catch (Exception e) {
                    logger.error("❌ Error adding Kubescape data to dashboard for cluster: {}", clusterName, e);
                }

                logger.info("✅ Complete dashboard data fetched successfully");
                return dashboard;

            } catch (Exception e) {
                logger.error("❌ Error fetching complete dashboard", e);
                return createEmptyDashboard(clusterName, region, version, status);
            }
        });
    }

    private EksCompleteDashboardDto createEmptyDashboard(String clusterName, String region, String version,
            String status) {
        EksCompleteDashboardDto dashboard = new EksCompleteDashboardDto();

        ClusterOverview overview = new ClusterOverview();
        overview.setClusterName(clusterName);
        overview.setRegion(region);
        overview.setVersion(version);
        overview.setStatus(status);
        overview.setTotalNodes(0);
        overview.setTotalPods(0);
        overview.setTotalNamespaces(0);
        overview.setCpuUsagePercent(0.0);
        overview.setMemoryUsagePercent(0.0);

        dashboard.setClusterOverview(overview);
        dashboard.setCostMetrics(new CostMetrics());
        dashboard.setSecurityMetrics(new SecurityMetrics());
        dashboard.setResourceUtilization(new ResourceUtilization());
        dashboard.setWorkloadHealth(new WorkloadHealth());

        return dashboard;
    }

    private ClusterOverview buildClusterOverview(String promUrl, String clusterName, String region, String version,
            String status) {
        ClusterOverview overview = new ClusterOverview();
        overview.setClusterName(clusterName);
        overview.setRegion(region);
        overview.setVersion(version);
        overview.setStatus(status);

        overview.setTotalNodes(prometheusService.getClusterNodeCount(promUrl, clusterName));
        overview.setTotalPods(prometheusService.getClusterPodCount(promUrl, clusterName));
        overview.setCpuUsagePercent(prometheusService.getClusterCpuUsage(promUrl, clusterName));
        overview.setMemoryUsagePercent(prometheusService.getClusterMemoryUsage(promUrl, clusterName));

        Map<String, String> namespaces = prometheusService.getNamespaceStatusPhase(promUrl, clusterName);
        overview.setTotalNamespaces(namespaces.size());

        return overview;
    }

    private CostMetrics buildCostMetrics(String promUrl, String clusterName) {
        CostMetrics cost = new CostMetrics();

        Double dailyCost = prometheusService.getClusterDailyCost(promUrl, clusterName);
        cost.setDailyCost(dailyCost != null ? dailyCost : 0.0);
        cost.setMonthlyCost((dailyCost != null ? dailyCost : 0.0) * 30);

        // ✅ Get costs by instance_id
        Map<String, Double> hourlyCostsByInstanceId = prometheusService.getNodeCostPerHour(promUrl, clusterName);
        Map<String, Double> dailyCostsByInstanceId = prometheusService.getNodeDailyCost(promUrl, clusterName);

        // ✅ Build instance_id to node_name mapping
        List<Map<String, String>> nodeInfoList = prometheusService.getNodeInfo(promUrl, clusterName);
        Map<String, String> instanceIdToNodeName = buildInstanceIdToNodeNameMap(nodeInfoList);

        List<CostMetrics.NodeCost> nodeCosts = new ArrayList<>();

        // ✅ Iterate through instance IDs and map to node names
        for (String instanceId : hourlyCostsByInstanceId.keySet()) {
            String nodeName = instanceIdToNodeName.getOrDefault(instanceId, instanceId); // Fallback to instance_id if
                                                                                         // mapping fails

            nodeCosts.add(new CostMetrics.NodeCost(
                    nodeName,
                    hourlyCostsByInstanceId.get(instanceId),
                    dailyCostsByInstanceId.getOrDefault(instanceId, 0.0),
                    "t3.medium"));
        }

        cost.setNodeCosts(nodeCosts);

        return cost;
    }

    private SecurityMetrics buildSecurityMetrics(String promUrl, String clusterName) {
        SecurityMetrics security = new SecurityMetrics();

        Double eventsLastHour = prometheusService.getFalcoEventsTotal(promUrl, clusterName);
        // ✅ FIX: Use Math.round() or .longValue() to safely convert Double to Long
        security.setEventsLastHour(eventsLastHour != null ? Math.round(eventsLastHour) : 0L);

        Double totalInputs = prometheusService.getFalcoInputsTotal(promUrl, clusterName);
        // ✅ FIX: Same here
        security.setTotalEvents(totalInputs != null ? Math.round(totalInputs) : 0L);

        List<Map<String, String>> eventsByPriority = prometheusService.getFalcoEventsByPriority(promUrl, clusterName);
        Map<String, Integer> priorityCount = new HashMap<>();
        List<SecurityMetrics.SecurityAlert> alerts = new ArrayList<>();
        Set<String> uniqueRules = new HashSet<>();

        for (Map<String, String> event : eventsByPriority) {
            String priority = event.get("priority");
            String rule = event.get("rule");
            String value = event.getOrDefault("value", "0");

            if (rule != null) {
                uniqueRules.add(rule);
            }

            priorityCount.merge(priority, 1, (a, b) -> a + b);

            // ✅ FIX: Parse as Double first, then convert to Long
            try {
                long eventValue = Math.round(Double.parseDouble(value));
                alerts.add(new SecurityMetrics.SecurityAlert(
                        rule,
                        priority,
                        event.getOrDefault("k8s_pod_name", "N/A"),
                        event.getOrDefault("k8s_ns_name", "N/A"),
                        eventValue));
            } catch (NumberFormatException e) {
                logger.warn("Failed to parse Falco event value: {}", value);
            }
        }

        security.setEventsByPriority(priorityCount);
        security.setAlertTypes(uniqueRules.size());
        security.setAlerts(alerts.stream().limit(20).collect(Collectors.toList()));

        return security;
    }

    private Map<String, String> buildInstanceIdToNodeNameMap(List<Map<String, String>> nodeInfoList) {
        Map<String, String> instanceIdToNodeName = new HashMap<>();

        for (Map<String, String> nodeInfo : nodeInfoList) {
            String providerId = nodeInfo.get("provider_id");
            String nodeName = nodeInfo.get("node");

            // Extract instance ID from provider_id (format: aws:///ap-south-1a/i-xxx)
            if (providerId != null && providerId.contains("/i-")) {
                String instanceId = providerId.substring(providerId.lastIndexOf("/") + 1);
                instanceIdToNodeName.put(instanceId, nodeName);
            }
        }

        return instanceIdToNodeName;
    }

    private ResourceUtilization buildResourceUtilization(String promUrl, String clusterName) {
        logger.info("🔍 === BUILDING RESOURCE UTILIZATION ===");
        ResourceUtilization resource = new ResourceUtilization();

        List<Map<String, String>> nodeInfoList = prometheusService.getNodeInfo(promUrl, clusterName);
        logger.info("🔍 Node info returned {} nodes", nodeInfoList != null ? nodeInfoList.size() : 0);

        if (nodeInfoList == null || nodeInfoList.isEmpty()) {
            logger.warn("⚠️ No node info available - returning empty resource utilization");
            resource.setNodes(new ArrayList<>());
            resource.setNamespaces(new HashMap<>());
            return resource;
        }

        // Log node names
        logger.info("🔍 Node names: {}",
                nodeInfoList.stream().map(n -> n.get("node")).collect(Collectors.toList()));

        Map<String, Map<String, Double>> nodeCapacity = prometheusService.getNodeCapacity(promUrl, clusterName);
        Map<String, Map<String, Double>> nodeAllocatable = prometheusService.getNodeAllocatable(promUrl, clusterName);
        Map<String, String> nodeConditions = prometheusService.getNodeConditions(promUrl, clusterName);
        Map<String, Double> nodeCostsByInstanceId = prometheusService.getNodeCostPerHour(promUrl, clusterName);
        Map<String, String> instanceIdToNodeName = buildInstanceIdToNodeNameMap(nodeInfoList);

        logger.info("🔍 Node capacity keys: {}", nodeCapacity.keySet());
        logger.info("🔍 Node allocatable keys: {}", nodeAllocatable.keySet());
        logger.info("🔍 Node conditions keys: {}", nodeConditions.keySet());
        logger.info("🔍 Node costs (by instanceId) keys: {}", nodeCostsByInstanceId.keySet());

        List<ResourceUtilization.NodeResource> nodes = new ArrayList<>();

        for (Map<String, String> nodeInfo : nodeInfoList) {
            String nodeName = nodeInfo.get("node");
            String providerId = nodeInfo.get("provider_id");

            logger.info("🔍 Processing node: {}, providerId: {}", nodeName, providerId);

            String instanceId = null;
            if (providerId != null && providerId.contains("/i-")) {
                instanceId = providerId.substring(providerId.lastIndexOf("/") + 1);
                logger.info("🔍 Extracted instanceId: {}", instanceId);
            }

            ResourceUtilization.NodeResource nodeRes = new ResourceUtilization.NodeResource();
            nodeRes.setNodeName(nodeName);
            nodeRes.setInstanceType("t3.medium");
            nodeRes.setZone(extractZone(providerId));
            nodeRes.setCondition(nodeConditions.getOrDefault(nodeName, "Unknown"));

            // Set capacity values
            if (nodeCapacity.containsKey(nodeName)) {
                Map<String, Double> capacity = nodeCapacity.get(nodeName);
                nodeRes.setCpuCapacity(capacity.getOrDefault("cpu", 0.0));
                nodeRes.setMemoryCapacity(capacity.getOrDefault("memory", 0.0));
                nodeRes.setPodsCapacity(capacity.getOrDefault("pods", 0.0));
                logger.info("✅ Set capacity for node {}", nodeName);
            } else {
                logger.warn("⚠️ No capacity data for node: {}", nodeName);
                nodeRes.setCpuCapacity(0.0);
                nodeRes.setMemoryCapacity(0.0);
                nodeRes.setPodsCapacity(0.0);
            }

            // Set allocatable values
            if (nodeAllocatable.containsKey(nodeName)) {
                Map<String, Double> allocatable = nodeAllocatable.get(nodeName);
                nodeRes.setCpuAllocatable(allocatable.getOrDefault("cpu", 0.0));
                nodeRes.setMemoryAllocatable(allocatable.getOrDefault("memory", 0.0));
                nodeRes.setPodsAllocatable(allocatable.getOrDefault("pods", 0.0));
                logger.info("✅ Set allocatable for node {}", nodeName);
            } else {
                logger.warn("⚠️ No allocatable data for node: {}", nodeName);
                nodeRes.setCpuAllocatable(0.0);
                nodeRes.setMemoryAllocatable(0.0);
                nodeRes.setPodsAllocatable(0.0);
            }

            nodeRes.setHourlyCost(instanceId != null ? nodeCostsByInstanceId.getOrDefault(instanceId, 0.0) : 0.0);

            nodes.add(nodeRes);
            logger.info("✅ Added node {} to resource list (total now: {})", nodeName, nodes.size());
        }

        logger.info("✅ === BUILT {} NODE RESOURCES ===", nodes.size());
        resource.setNodes(nodes);
        resource.setNamespaces(new HashMap<>());
        return resource;
    }

    private WorkloadHealth buildWorkloadHealth(String promUrl, String clusterName) {
        WorkloadHealth workload = new WorkloadHealth();

        workload.setPods(buildPodHealth(promUrl, clusterName));
        workload.setDeployments(buildDeploymentHealth(promUrl, clusterName));
        workload.setDaemonsets(buildDaemonsetHealth(promUrl, clusterName));

        return workload;
    }

    private List<WorkloadHealth.PodHealth> buildPodHealth(String promUrl, String clusterName) {
        List<Map<String, String>> podInfoList = prometheusService.getPodInfo(promUrl, clusterName);
        Map<String, String> podPhases = prometheusService.getPodStatusPhase(promUrl, clusterName);
        Map<String, Boolean> podReady = prometheusService.getPodContainerStatus(promUrl, clusterName);
        Map<String, Double> podCpu = prometheusService.getPodCpuUsage(promUrl, clusterName);
        Map<String, Double> podMem = prometheusService.getPodMemoryUsage(promUrl, clusterName);

        Map<String, Double> containerCpu = prometheusService.getContainerCpuUsage(promUrl, clusterName);
        Map<String, Double> containerMem = prometheusService.getContainerMemoryUsage(promUrl, clusterName);
        Map<String, Double> containerFs = prometheusService.getContainerFsUsage(promUrl, clusterName);

        // ✅ DEBUG LOGGING
        logger.info("🔍 === CONTAINER DEBUG ===");
        logger.info("🔍 Container CPU keys sample (first 5): {}",
                containerCpu.keySet().stream().limit(5).collect(Collectors.toList()));
        logger.info("🔍 Pod names sample (first 5): {}",
                podInfoList.stream().map(p -> p.get("pod")).limit(5).collect(Collectors.toList()));

        List<WorkloadHealth.PodHealth> pods = new ArrayList<>();

        for (Map<String, String> podInfo : podInfoList) {
            String podName = podInfo.get("pod");
            WorkloadHealth.PodHealth pod = new WorkloadHealth.PodHealth();

            pod.setName(podName);
            pod.setNamespace(podInfo.get("namespace"));
            pod.setPhase(podPhases.getOrDefault(podName, "Unknown"));
            pod.setNode(podInfo.get("node"));
            pod.setReady(podReady.getOrDefault(podName, false));
            pod.setRestarts(0);
            pod.setCpuUsage(podCpu.getOrDefault(podName, 0.0));
            pod.setMemoryUsage(podMem.getOrDefault(podName, 0.0));

            Map<String, WorkloadHealth.PodHealth.ContainerMetrics> containers = new HashMap<>();
            int matchCount = 0;

            // ✅ IMPROVED MATCHING LOGIC
            for (Map.Entry<String, Double> entry : containerCpu.entrySet()) {
                String key = entry.getKey(); // Format: "pod-name/container-name"

                if (key.startsWith(podName + "/")) {
                    matchCount++;
                    String containerName = key.substring(podName.length() + 1);

                    logger.debug("✅ MATCH: pod={}, key={}, container={}", podName, key, containerName);

                    WorkloadHealth.PodHealth.ContainerMetrics container = new WorkloadHealth.PodHealth.ContainerMetrics();
                    container.setName(containerName);
                    container.setCpuUsage(entry.getValue());
                    container.setMemoryUsage(containerMem.getOrDefault(key, 0.0));
                    container.setFsUsage(containerFs.getOrDefault(key, 0.0));
                    containers.put(containerName, container);
                }
            }

            if (matchCount == 0) {
                logger.warn("❌ NO MATCHES for pod: {}", podName);
            } else {
                logger.info("✅ Found {} containers for pod: {}", matchCount, podName);
            }

            pod.setContainers(containers);
            pods.add(pod);
        }

        logger.info("✅ Total pods processed: {}", pods.size());
        return pods;
    }

    private List<WorkloadHealth.DeploymentHealth> buildDeploymentHealth(String promUrl, String clusterName) {
        Map<String, Double> available = prometheusService.getDeploymentReplicasAvailable(promUrl, clusterName);
        Map<String, Double> desired = prometheusService.getDeploymentReplicasDesired(promUrl, clusterName);

        List<WorkloadHealth.DeploymentHealth> deployments = new ArrayList<>();

        for (String deploymentName : desired.keySet()) {
            WorkloadHealth.DeploymentHealth deployment = new WorkloadHealth.DeploymentHealth();
            deployment.setName(deploymentName);
            deployment.setNamespace("default");

            int desiredCount = desired.get(deploymentName).intValue();
            int availableCount = available.getOrDefault(deploymentName, 0.0).intValue();

            deployment.setDesired(desiredCount);
            deployment.setAvailable(availableCount);
            deployment.setUnavailable(desiredCount - availableCount);
            deployment.setUpdated(availableCount);

            double healthScore = desiredCount > 0 ? (availableCount * 100.0 / desiredCount) : 0.0;
            deployment.setHealthScore(healthScore);

            deployments.add(deployment);
        }

        return deployments;
    }

    private List<WorkloadHealth.DaemonsetHealth> buildDaemonsetHealth(String promUrl, String clusterName) {
        Map<String, Double> desired = prometheusService.getDaemonsetDesired(promUrl, clusterName);
        Map<String, Double> ready = prometheusService.getDaemonsetReady(promUrl, clusterName);

        List<WorkloadHealth.DaemonsetHealth> daemonsets = new ArrayList<>();

        for (String daemonsetName : desired.keySet()) {
            WorkloadHealth.DaemonsetHealth daemonset = new WorkloadHealth.DaemonsetHealth();
            daemonset.setName(daemonsetName);
            daemonset.setNamespace("kube-system");

            int desiredCount = desired.get(daemonsetName).intValue();
            int readyCount = ready.getOrDefault(daemonsetName, 0.0).intValue();

            daemonset.setDesired(desiredCount);
            daemonset.setReady(readyCount);
            daemonset.setUnavailable(desiredCount - readyCount);

            double healthScore = desiredCount > 0 ? (readyCount * 100.0 / desiredCount) : 0.0;
            daemonset.setHealthScore(healthScore);

            daemonsets.add(daemonset);
        }

        return daemonsets;
    }

    private String extractZone(String providerId) {
        if (providerId != null && providerId.startsWith("aws:///")) {
            String[] parts = providerId.split("/");
            if (parts.length >= 4) {
                return parts[3];
            }
        }
        return "N/A";
    }
}