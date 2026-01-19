package com.xammer.cloud.service;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.KubernetesCluster;
import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.dto.k8s.EksCompleteDashboardDto;
import com.xammer.cloud.dto.k8s.EksCompleteDashboardDto.*;
import com.xammer.cloud.dto.k8s.KubescapeDashboard;
import com.xammer.cloud.dto.k8s.TrivyDashboard;
import com.xammer.cloud.dto.k8s.KarpenterDashboard;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import com.xammer.cloud.repository.KubernetesClusterRepository;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.ContainerStatus;
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
    private static final String EKS_DASHBOARD_CACHE_PREFIX = "EKS_DASHBOARD::";
    private static final int CACHE_TTL_MINUTES = 2; // Cache for 2 minutes

    private final PrometheusService prometheusService;
    private final KubernetesClusterRepository kubernetesClusterRepository;
    private final KubernetesClusterConfigRepository clusterConfigRepository;
    private final KubescapeService kubescapeService;
    private final TrivyService trivyService;
    private final KarpenterService karpenterService;
    private final K8sClientFactory k8sClientFactory;
    private final RedisCacheService redisCacheService;

    public EksDashboardService(PrometheusService prometheusService,
            KubernetesClusterRepository kubernetesClusterRepository,
            KubernetesClusterConfigRepository clusterConfigRepository,
            KubescapeService kubescapeService,
            TrivyService trivyService,
            KarpenterService karpenterService,
            K8sClientFactory k8sClientFactory,
            RedisCacheService redisCacheService) {
        this.prometheusService = prometheusService;
        this.kubernetesClusterRepository = kubernetesClusterRepository;
        this.clusterConfigRepository = clusterConfigRepository;
        this.kubescapeService = kubescapeService;
        this.trivyService = trivyService;
        this.karpenterService = karpenterService;
        this.k8sClientFactory = k8sClientFactory;
        this.redisCacheService = redisCacheService;
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

    /**
     * Backward-compatible method - defaults to using cache
     */
    public CompletableFuture<EksCompleteDashboardDto> getCompleteDashboard(
            CloudAccount account, String clusterName, String region, String version, String status) {
        return getCompleteDashboard(account, clusterName, region, version, status, false);
    }

    /**
     * Get complete dashboard with cache support
     * 
     * @param forceRefresh if true, bypasses cache and fetches fresh data
     */
    public CompletableFuture<EksCompleteDashboardDto> getCompleteDashboard(
            CloudAccount account, String clusterName, String region, String version, String status,
            boolean forceRefresh) {

        String cacheKey = EKS_DASHBOARD_CACHE_PREFIX + account.getAwsAccountId() + "::" + clusterName;

        return CompletableFuture.supplyAsync(() -> {
            try {
                // Check cache first (unless forceRefresh is true or Redis unavailable)
                if (!forceRefresh && redisCacheService != null) {
                    try {
                        Optional<EksCompleteDashboardDto> cached = redisCacheService.get(cacheKey,
                                EksCompleteDashboardDto.class);
                        if (cached.isPresent()) {
                            logger.info("📦 Returning cached EKS dashboard for cluster: {}", clusterName);
                            return cached.get();
                        }
                    } catch (Exception cacheEx) {
                        logger.warn("⚠️ Cache read failed, fetching fresh data: {}", cacheEx.getMessage());
                    }
                }

                String promUrl = getPrometheusUrl(account, clusterName);

                if (promUrl == null) {
                    logger.warn("⚠️ No Prometheus URL found for cluster: {}. Returning empty dashboard.", clusterName);
                    return createEmptyDashboard(clusterName, region, version, status);
                }

                logger.info("🎯 Fetching fresh dashboard data for cluster: {} (forceRefresh={})", clusterName,
                        forceRefresh);

                EksCompleteDashboardDto dashboard = new EksCompleteDashboardDto();

                // Build sections
                dashboard.setClusterOverview(buildClusterOverview(promUrl, clusterName, region, version, status));
                dashboard.setCostMetrics(buildCostMetrics(promUrl, clusterName));
                dashboard.setSecurityMetrics(buildSecurityMetrics(promUrl, clusterName));
                dashboard.setResourceUtilization(buildResourceUtilization(promUrl, clusterName));
                dashboard.setWorkloadHealth(buildWorkloadHealth(promUrl, clusterName));

                // ✅ NEW: Add detailed pod and enhanced node information
                dashboard.setPodDetails(buildPodDetails(promUrl, clusterName));
                dashboard.setNodeDetails(buildNodeDetails(promUrl, clusterName));

                // ✅ NEW: Add aggregated metrics for new tabs
                dashboard.setPodMetrics(buildPodMetrics(promUrl, clusterName));
                dashboard.setContainerMetrics(buildContainerMetrics(promUrl, clusterName));
                dashboard.setNodeMetrics(buildNodeMetrics(promUrl, clusterName));

                // Fetch K8s integrations (Kubescape, Trivy, Karpenter)
                try {
                    String awsAccountIdStr = account.getAwsAccountId();
                    Long providerId = null;
                    if (awsAccountIdStr != null && awsAccountIdStr.matches("\\d+")) {
                        providerId = Long.valueOf(awsAccountIdStr);
                    }

                    logger.info("🔍 Looking up cluster config for: {}. DB ID: {}, AWS ID (as Long): {}",
                            clusterName, account.getId(), providerId);

                    // Step 1: Try AWS Account ID first (some DBs store this)
                    Optional<KubernetesClusterConfig> configOpt = providerId != null
                            ? clusterConfigRepository.findByCloudAccountIdAndClusterName(providerId, clusterName)
                            : Optional.empty();

                    if (configOpt.isPresent()) {
                        logger.info("✅ Found cluster config using AWS ID: {}", providerId);
                    }

                    if (configOpt.isEmpty()) {
                        logger.warn("⚠️ Cluster config NOT found using AWS ID, trying DB ID FALLBACK: {}",
                                account.getId());
                        configOpt = clusterConfigRepository.findByCloudAccountIdAndClusterName(account.getId(),
                                clusterName);
                    }

                    if (configOpt.isPresent()) {
                        KubernetesClusterConfig config = configOpt.get();
                        logger.info("✅ Found cluster config: id={}, clusterName={}, kubeconfigLength={}",
                                config.getId(), config.getClusterName(),
                                config.getKubeconfigYaml() != null ? config.getKubeconfigYaml().length() : 0);
                        String kubeconfig = config.getKubeconfigYaml();
                        if (kubeconfig != null && !kubeconfig.isEmpty()) {
                            // Step 3: Fix Resource Cleanup (Try-With-Resources)
                            logger.info("🔗 Creating K8s client from kubeconfig...");
                            try (KubernetesClient client = k8sClientFactory.createFromKubeconfig(kubeconfig)) {
                                logger.info("✅ K8s client created successfully");

                                // 1. Kubescape (Conditional)
                                if (Boolean.TRUE.equals(config.getKubescapeEnabled())) {
                                    KubescapeDashboard kDashboard = kubescapeService.fetchDashboardData(
                                            client,
                                            clusterName,
                                            awsAccountIdStr != null ? awsAccountIdStr
                                                    : String.valueOf(account.getId()));
                                    dashboard.setKubescape(kDashboard);
                                } else {
                                    logger.info("ℹ️ Kubescape is NOT enabled for cluster: {}", clusterName);
                                }

                                // 2. Trivy (Independent)
                                TrivyDashboard tDashboard = trivyService.fetchDashboardData(client);
                                dashboard.setTrivy(tDashboard);

                                // 3. Karpenter (Independent)
                                try {
                                    KarpenterDashboard karDashboard = karpenterService.fetchDashboardData(client);
                                    dashboard.setKarpenter(karDashboard);
                                    logger.info("📊 Karpenter data fetched: installed={}, nodePools={}",
                                            karDashboard.isInstalled(), karDashboard.getTotalNodePools());
                                } catch (Exception karpenterEx) {
                                    logger.error("❌ Error fetching Karpenter data for cluster: {}", clusterName,
                                            karpenterEx);
                                }

                                // 4. Pod Enrichment (Independent)
                                WorkloadHealth currentWorkload = dashboard.getWorkloadHealth();
                                if (currentWorkload != null) {
                                    List<WorkloadHealth.PodHealth> enrichedPods = buildPodHealth(promUrl, clusterName,
                                            client);
                                    currentWorkload.setPods(enrichedPods);
                                    logger.info("✅ Pod data enriched with container statuses from K8s API");
                                }

                                logger.info("✅ K8s integrations (Kubescape/Trivy/Karpenter) processed for cluster: {}",
                                        clusterName);
                            }
                        } else {
                            logger.warn("⚠️ Kubeconfig is EMPTY for cluster: {}", clusterName);
                        }
                    } else {
                        logger.warn("⚠️ No cluster config found for cluster: {}. All ID lookups failed.", clusterName);
                    }
                } catch (Exception e) {
                    logger.error("❌ Error adding Kubescape data to dashboard for cluster: {}", clusterName, e);
                }

                // Save to cache (if Redis available)
                if (redisCacheService != null) {
                    try {
                        redisCacheService.put(cacheKey, dashboard, CACHE_TTL_MINUTES);
                        logger.info("✅ Complete dashboard data fetched and cached for {} mins", CACHE_TTL_MINUTES);
                    } catch (Exception cacheEx) {
                        logger.warn("⚠️ Cache write failed: {}", cacheEx.getMessage());
                    }
                } else {
                    logger.info("✅ Complete dashboard data fetched (caching disabled)");
                }
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

        Map<String, String> namespaces = prometheusService.getNamespaceStatusPhase(promUrl, clusterName);
        overview.setTotalNamespaces(namespaces.size());

        overview.setCpuUsagePercent(prometheusService.getClusterCpuUsage(promUrl, clusterName));
        overview.setCpuUsageMillicores(prometheusService.getClusterCpuUsageMillicores(promUrl, clusterName));
        overview.setCpuCapacityMillicores(prometheusService.getClusterCpuCapacityMillicores(promUrl, clusterName));
        overview.setMemoryUsagePercent(prometheusService.getClusterMemoryUsage(promUrl, clusterName));

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
        Map<String, Double> nodeCpuUsage = prometheusService.getNodeCpuUsage(promUrl, clusterName);
        Map<String, Double> nodeCpuMillicores = prometheusService.getNodeCpuUsageMillicores(promUrl, clusterName);
        Map<String, Double> nodeMemUsage = prometheusService.getNodeMemoryUsage(promUrl, clusterName);

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
            nodeRes.setCpuUsagePercent(nodeCpuUsage.getOrDefault(nodeName, 0.0));
            nodeRes.setCpuUsageMillicores(nodeCpuMillicores.getOrDefault(nodeName, 0.0));
            nodeRes.setMemoryUsagePercent(nodeMemUsage.getOrDefault(nodeName, 0.0));

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
        return buildPodHealth(promUrl, clusterName, null);
    }

    private List<WorkloadHealth.PodHealth> buildPodHealth(String promUrl, String clusterName,
            KubernetesClient k8sClient) {
        List<Map<String, String>> podInfoList = prometheusService.getPodInfo(promUrl, clusterName);
        Map<String, String> podPhases = prometheusService.getPodStatusPhase(promUrl, clusterName);
        Map<String, Boolean> podReady = prometheusService.getPodContainerStatus(promUrl, clusterName);
        Map<String, Double> podCpu = prometheusService.getPodCpuUsage(promUrl, clusterName);
        Map<String, Double> podMem = prometheusService.getPodMemoryUsage(promUrl, clusterName);

        Map<String, Double> containerCpu = prometheusService.getContainerCpuUsage(promUrl, clusterName);
        Map<String, Double> containerMem = prometheusService.getContainerMemoryUsage(promUrl, clusterName);
        Map<String, Double> containerFs = prometheusService.getContainerFsUsage(promUrl, clusterName);

        // Fetch actual pods from K8s API if client is provided
        Map<String, io.fabric8.kubernetes.api.model.Pod> k8sPods = new HashMap<>();
        if (k8sClient != null) {
            try {
                k8sClient.pods().inAnyNamespace().list().getItems().forEach(pod -> {
                    k8sPods.put(pod.getMetadata().getName(), pod);
                });
                logger.info("✅ Fetched {} pods from K8s API for container status enrichment", k8sPods.size());
            } catch (Exception e) {
                logger.warn("⚠️ Could not fetch pods from K8s API: {}", e.getMessage());
            }
        }

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

            List<WorkloadHealth.PodHealth.ContainerMetrics> containers = new ArrayList<>();
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
                    containers.add(container);
                }
            }

            if (matchCount == 0) {
                logger.warn("❌ NO MATCHES for pod: {}", podName);
            } else {
                logger.info("✅ Found {} containers for pod: {}", matchCount, podName);
            }

            pod.setContainers(containers);

            // ✅ NEW: Populate containerStatuses from K8s API (real lifecycle state)
            if (k8sClient != null && k8sPods.containsKey(podName)) {
                io.fabric8.kubernetes.api.model.Pod k8sPod = k8sPods.get(podName);
                List<WorkloadHealth.PodHealth.ContainerStatus> containerStatuses = new ArrayList<>();

                if (k8sPod.getStatus() != null && k8sPod.getStatus().getContainerStatuses() != null) {
                    for (io.fabric8.kubernetes.api.model.ContainerStatus k8sStatus : k8sPod.getStatus()
                            .getContainerStatuses()) {
                        WorkloadHealth.PodHealth.ContainerStatus status = new WorkloadHealth.PodHealth.ContainerStatus();
                        status.setName(k8sStatus.getName());
                        status.setRestartCount(k8sStatus.getRestartCount());
                        status.setImage(k8sStatus.getImage());
                        status.setReady(k8sStatus.getReady());

                        // Determine state from K8s container state
                        if (k8sStatus.getState() != null) {
                            if (k8sStatus.getState().getRunning() != null) {
                                status.setState("running");
                            } else if (k8sStatus.getState().getWaiting() != null) {
                                status.setState("waiting");
                            } else if (k8sStatus.getState().getTerminated() != null) {
                                status.setState("terminated");
                            }
                        }

                        containerStatuses.add(status);
                    }
                }

                pod.setContainerStatuses(containerStatuses);
                logger.debug("✅ Added {} container statuses for pod: {}", containerStatuses.size(), podName);
            }

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

    /**
     * ✅ NEW: Build comprehensive pod details with container-level metrics
     * Shows detailed information like Cast.ai pod view
     */
    private List<EksCompleteDashboardDto.PodDetail> buildPodDetails(String promUrl, String clusterName) {
        logger.info("🔍 === BUILDING COMPREHENSIVE POD DETAILS ===");

        // Fetch all pod-related data from Prometheus
        List<Map<String, String>> podInfoList = prometheusService.getPodInfo(promUrl, clusterName);
        Map<String, String> podPhases = prometheusService.getPodStatusPhase(promUrl, clusterName);
        Map<String, Boolean> podReady = prometheusService.getPodContainerStatus(promUrl, clusterName);
        Map<String, Map<String, String>> podOwners = prometheusService.getPodOwner(promUrl, clusterName);
        Map<String, Double> podRestarts = prometheusService.getPodRestarts(promUrl, clusterName);
        Map<String, Double> podCreationTimes = prometheusService.getPodCreationTime(promUrl, clusterName);

        // ✅ NEW: Resource requests and limits
        Map<String, Double> podCpuRequests = prometheusService.getPodCpuRequests(promUrl, clusterName);
        Map<String, Double> podMemRequests = prometheusService.getPodMemoryRequests(promUrl, clusterName);
        Map<String, Double> podCpuLimits = prometheusService.getPodCpuLimits(promUrl, clusterName);
        Map<String, Double> podMemLimits = prometheusService.getPodMemoryLimits(promUrl, clusterName);

        // ✅ NEW: Actual usage
        Map<String, Double> podCpuUsage = prometheusService.getPodCpuUsageFromContainers(promUrl, clusterName);
        Map<String, Double> podMemUsage = prometheusService.getPodMemoryUsage(promUrl, clusterName);

        // ✅ Container-level data
        List<Map<String, String>> containerInfoList = prometheusService.getContainerInfo(promUrl, clusterName);
        Map<String, Double> containerCpu = prometheusService.getContainerCpuUsage(promUrl, clusterName);
        Map<String, Double> containerMem = prometheusService.getContainerMemoryUsage(promUrl, clusterName);
        Map<String, Double> containerFs = prometheusService.getContainerFsUsage(promUrl, clusterName);
        Map<String, Boolean> containerRunning = prometheusService.getContainerRunningState(promUrl, clusterName);
        Map<String, Double> containerRestarts = prometheusService.getContainerRestarts(promUrl, clusterName);

        List<EksCompleteDashboardDto.PodDetail> pods = new ArrayList<>();

        for (Map<String, String> podInfo : podInfoList) {
            String podName = podInfo.get("pod");
            String namespace = podInfo.get("namespace");

            EksCompleteDashboardDto.PodDetail pod = new EksCompleteDashboardDto.PodDetail();
            pod.setName(podName);
            pod.setNamespace(namespace);
            pod.setPhase(podPhases.getOrDefault(podName, "Unknown"));
            pod.setNode(podInfo.get("node"));
            pod.setPodIp(podInfo.get("pod_ip"));
            pod.setReady(podReady.getOrDefault(podName, false));
            pod.setRestarts(podRestarts.getOrDefault(podName, 0.0).intValue());

            // Convert creation timestamp to age string
            Double creationTimestamp = podCreationTimes.get(podName);
            if (creationTimestamp != null) {
                long ageSeconds = (long) (System.currentTimeMillis() / 1000.0 - creationTimestamp);
                pod.setAge(formatAge(ageSeconds));
            } else {
                pod.setAge("N/A");
            }

            // ✅ Owner information (Deployment/StatefulSet/DaemonSet)
            Map<String, String> owner = podOwners.get(podName);
            if (owner != null) {
                pod.setOwnerKind(owner.get("kind"));
                pod.setOwnerName(owner.get("name"));
            } else {
                pod.setOwnerKind("Unknown");
                pod.setOwnerName("N/A");
            }

            // ✅ Resource requests and limits
            pod.setCpuRequest(podCpuRequests.getOrDefault(podName, 0.0));
            pod.setMemoryRequest(podMemRequests.getOrDefault(podName, 0.0));
            pod.setCpuLimit(podCpuLimits.getOrDefault(podName, 0.0));
            pod.setMemoryLimit(podMemLimits.getOrDefault(podName, 0.0));

            // ✅ Actual usage
            pod.setCpuUsage(podCpuUsage.getOrDefault(podName, 0.0));
            pod.setMemoryUsage(podMemUsage.getOrDefault(podName, 0.0));

            // ✅ Build container list
            List<EksCompleteDashboardDto.PodDetail.ContainerDetail> containers = new ArrayList<>();

            for (Map<String, String> containerInfo : containerInfoList) {
                if (podName.equals(containerInfo.get("pod")) && namespace.equals(containerInfo.get("namespace"))) {
                    String containerName = containerInfo.get("container");
                    String containerKey = podName + "/" + containerName;

                    EksCompleteDashboardDto.PodDetail.ContainerDetail container = new EksCompleteDashboardDto.PodDetail.ContainerDetail();
                    container.setName(containerName);
                    container.setImage(containerInfo.get("image"));
                    container.setImageId(containerInfo.get("image_id"));
                    container.setCpuUsage(containerCpu.getOrDefault(containerKey, 0.0));
                    container.setMemoryUsage(containerMem.getOrDefault(containerKey, 0.0));
                    container.setFsUsage(containerFs.getOrDefault(containerKey, 0.0));
                    container.setRunning(containerRunning.getOrDefault(containerKey, false));
                    container.setRestarts(containerRestarts.getOrDefault(containerKey, 0.0).intValue());

                    containers.add(container);
                }
            }

            pod.setContainers(containers);
            pod.setContainerCount(containers.size());

            pods.add(pod);
        }

        logger.info("✅ Built {} pod details with container information", pods.size());
        return pods;
    }

    /**
     * ✅ NEW: Build comprehensive node details with capacity, conditions, and pod
     * placement
     * Shows detailed information like Cast.ai node view
     */
    private List<EksCompleteDashboardDto.NodeDetail> buildNodeDetails(String promUrl, String clusterName) {
        logger.info("🔍 === BUILDING COMPREHENSIVE NODE DETAILS ===");

        List<Map<String, String>> nodeInfoList = prometheusService.getNodeInfo(promUrl, clusterName);
        Map<String, Map<String, Double>> nodeCapacity = prometheusService.getNodeCapacity(promUrl, clusterName);
        Map<String, Map<String, Double>> nodeAllocatable = prometheusService.getNodeAllocatable(promUrl, clusterName);
        Map<String, String> nodeConditions = prometheusService.getNodeConditions(promUrl, clusterName);
        Map<String, Double> nodeCpuUsage = prometheusService.getNodeCpuUsage(promUrl, clusterName);
        Map<String, Double> nodeCpuMillicores = prometheusService.getNodeCpuUsageMillicores(promUrl, clusterName);
        Map<String, Double> nodeMemUsage = prometheusService.getNodeMemoryUsage(promUrl, clusterName);

        // ✅ NEW: Get all node conditions (Ready, DiskPressure, MemoryPressure,
        // PIDPressure)
        Map<String, List<String>> allNodeConditions = prometheusService.getNodeAllConditions(promUrl, clusterName);

        // ✅ NEW: Get pods per node
        List<Map<String, String>> podInfoList = prometheusService.getPodInfo(promUrl, clusterName);
        Map<String, List<String>> podsPerNode = new HashMap<>();
        for (Map<String, String> podInfo : podInfoList) {
            String nodeName = podInfo.get("node");
            String podName = podInfo.get("pod");
            String namespace = podInfo.get("namespace");
            podsPerNode.computeIfAbsent(nodeName, k -> new ArrayList<>())
                    .add(namespace + "/" + podName);
        }

        List<EksCompleteDashboardDto.NodeDetail> nodes = new ArrayList<>();

        for (Map<String, String> nodeInfo : nodeInfoList) {
            String nodeName = nodeInfo.get("node");
            String providerId = nodeInfo.get("provider_id");

            EksCompleteDashboardDto.NodeDetail node = new EksCompleteDashboardDto.NodeDetail();
            node.setName(nodeName);
            node.setInternalIp(nodeInfo.get("internal_ip"));
            node.setHostname(nodeName);
            node.setStatus(nodeConditions.getOrDefault(nodeName, "Unknown"));
            node.setKubeletVersion(nodeInfo.get("kubelet_version"));
            node.setOsImage(nodeInfo.get("os_image"));
            node.setKernelVersion(nodeInfo.get("kernel_version"));
            node.setContainerRuntime(nodeInfo.get("container_runtime_version"));

            // ✅ Extract instance ID and zone
            if (providerId != null && providerId.contains("/i-")) {
                String instanceId = providerId.substring(providerId.lastIndexOf("/") + 1);
                node.setInstanceId(instanceId);
            }
            node.setZone(extractZone(providerId));

            // ✅ Capacity
            if (nodeCapacity.containsKey(nodeName)) {
                Map<String, Double> capacity = nodeCapacity.get(nodeName);
                node.setCpuCapacity(capacity.getOrDefault("cpu", 0.0));
                node.setMemoryCapacity(capacity.getOrDefault("memory", 0.0));
                node.setPodsCapacity(capacity.getOrDefault("pods", 0.0).intValue());
            }

            // ✅ Allocatable
            if (nodeAllocatable.containsKey(nodeName)) {
                Map<String, Double> allocatable = nodeAllocatable.get(nodeName);
                node.setCpuAllocatable(allocatable.getOrDefault("cpu", 0.0));
                node.setMemoryAllocatable(allocatable.getOrDefault("memory", 0.0));
                node.setPodsAllocatable(allocatable.getOrDefault("pods", 0.0).intValue());
            }

            // ✅ Current usage
            node.setCpuUsagePercent(nodeCpuUsage.getOrDefault(nodeName, 0.0));
            node.setCpuUsageMillicores(nodeCpuMillicores.getOrDefault(nodeName, 0.0));
            node.setMemoryUsagePercent(nodeMemUsage.getOrDefault(nodeName, 0.0));

            // ✅ Conditions (Ready, DiskPressure, MemoryPressure, PIDPressure)
            List<String> conditions = allNodeConditions.getOrDefault(nodeName, new ArrayList<>());
            node.setConditions(conditions);

            // ✅ Pods on this node
            List<String> pods = podsPerNode.getOrDefault(nodeName, new ArrayList<>());
            node.setPods(pods);
            node.setPodCount(pods.size());

            nodes.add(node);
        }

        logger.info("✅ Built {} node details with conditions and pod placement", nodes.size());
        return nodes;
    }

    /**
     * Format age in seconds to human-readable string (e.g., "5d 12h", "3h 25m",
     * "45m")
     */
    private String formatAge(long ageSeconds) {
        if (ageSeconds < 0)
            return "N/A";

        long days = ageSeconds / 86400;
        long hours = (ageSeconds % 86400) / 3600;
        long minutes = (ageSeconds % 3600) / 60;

        if (days > 0) {
            return String.format("%dd %dh", days, hours);
        } else if (hours > 0) {
            return String.format("%dh %dm", hours, minutes);
        } else {
            return String.format("%dm", minutes);
        }
    }

    // ============================================================================================
    // ✅ NEW: AGGREGATION METHODS FOR DEDICATED PODS/CONTAINERS/NODES TABS
    // These methods are ADDITIVE - they don't modify existing functionality
    // ============================================================================================

    /**
     * Build comprehensive pod metrics for dedicated Pods tab
     */
    private PodMetrics buildPodMetrics(String promUrl, String clusterName) {
        PodMetrics metrics = new PodMetrics();

        try {
            // Get pod status phase counts
            Map<String, Integer> phaseCounts = prometheusService.getPodStatusPhaseCounts(promUrl, clusterName);
            metrics.setRunningCount(phaseCounts.getOrDefault("Running", 0));
            metrics.setPendingCount(phaseCounts.getOrDefault("Pending", 0));
            metrics.setFailedCount(phaseCounts.getOrDefault("Failed", 0));
            metrics.setSucceededCount(phaseCounts.getOrDefault("Succeeded", 0));

            // Get QoS class counts
            Map<String, Integer> qosCounts = prometheusService.getPodQosClassCounts(promUrl, clusterName);
            metrics.setQosGuaranteed(qosCounts.getOrDefault("Guaranteed", 0));
            metrics.setQosBurstable(qosCounts.getOrDefault("Burstable", 0));
            metrics.setQosBestEffort(qosCounts.getOrDefault("BestEffort", 0));

            // Get total container count
            List<Map<String, String>> containerInfo = prometheusService.getContainerInfo(promUrl, clusterName);
            metrics.setTotalContainers(containerInfo != null ? containerInfo.size() : 0);

            // Get pods with restarts
            Map<String, Integer> restartCounts = prometheusService.getPodRestartCounts(promUrl, clusterName);
            List<PodMetrics.PodRestartInfo> podsWithRestarts = new ArrayList<>();
            for (Map.Entry<String, Integer> entry : restartCounts.entrySet()) {
                String[] parts = entry.getKey().split("/");
                if (parts.length == 2) {
                    PodMetrics.PodRestartInfo info = new PodMetrics.PodRestartInfo();
                    info.setNamespace(parts[0]);
                    info.setName(parts[1]);
                    info.setRestarts(entry.getValue());
                    info.setStatus("Running"); // Default status
                    info.setLastRestartTime("N/A"); // Could be enhanced with actual timestamp
                    podsWithRestarts.add(info);
                }
            }
            // Sort by restart count descending
            podsWithRestarts.sort((a, b) -> Integer.compare(b.getRestarts(), a.getRestarts()));
            metrics.setPodsWithRestarts(podsWithRestarts);

            // ✅ NEW: Aggregate CPU/Memory requests, limits, and usage for charts
            Map<String, Double> cpuRequests = prometheusService.getPodCpuRequests(promUrl, clusterName);
            Map<String, Double> cpuLimits = prometheusService.getPodCpuLimits(promUrl, clusterName);
            Map<String, Double> cpuUsage = prometheusService.getPodCpuUsage(promUrl, clusterName);
            Map<String, Double> memoryRequests = prometheusService.getPodMemoryRequests(promUrl, clusterName);
            Map<String, Double> memoryLimits = prometheusService.getPodMemoryLimits(promUrl, clusterName);
            Map<String, Double> memoryUsage = prometheusService.getPodMemoryUsage(promUrl, clusterName);

            // Calculate totals
            double totalCpuRequested = cpuRequests.values().stream().mapToDouble(Double::doubleValue).sum();
            double totalCpuLimited = cpuLimits.values().stream().mapToDouble(Double::doubleValue).sum();
            double totalCpuUsed = cpuUsage.values().stream().mapToDouble(Double::doubleValue).sum();
            double totalMemoryRequested = memoryRequests.values().stream().mapToDouble(Double::doubleValue).sum();
            double totalMemoryLimited = memoryLimits.values().stream().mapToDouble(Double::doubleValue).sum();
            double totalMemoryUsed = memoryUsage.values().stream().mapToDouble(Double::doubleValue).sum();

            metrics.setTotalCpuRequested(totalCpuRequested);
            metrics.setTotalCpuLimited(totalCpuLimited);
            metrics.setTotalCpuUsed(totalCpuUsed);
            metrics.setTotalMemoryRequested(totalMemoryRequested);
            metrics.setTotalMemoryLimited(totalMemoryLimited);
            metrics.setTotalMemoryUsed(totalMemoryUsed);

            logger.info(
                    "✅ Built pod metrics: {} running, {} pending, {} failed, CPU: {}/{}/{} cores, Memory: {}/{}/{} Mi",
                    metrics.getRunningCount(), metrics.getPendingCount(), metrics.getFailedCount(),
                    String.format("%.2f", totalCpuRequested), String.format("%.2f", totalCpuLimited),
                    String.format("%.2f", totalCpuUsed),
                    String.format("%.0f", totalMemoryRequested), String.format("%.0f", totalMemoryLimited),
                    String.format("%.0f", totalMemoryUsed));

        } catch (Exception e) {
            logger.error("❌ Error building pod metrics", e);
        }

        return metrics;
    }

    /**
     * Build comprehensive container metrics for dedicated Containers tab
     */
    private ContainerMetrics buildContainerMetrics(String promUrl, String clusterName) {
        ContainerMetrics metrics = new ContainerMetrics();

        try {
            // Get container state counts
            Map<String, Integer> stateCounts = prometheusService.getContainerStateCounts(promUrl, clusterName);
            metrics.setRunningCount(stateCounts.getOrDefault("Running", 0));
            metrics.setWaitingCount(stateCounts.getOrDefault("Waiting", 0));
            metrics.setTerminatedCount(stateCounts.getOrDefault("Terminated", 0));

            // Get total restarts
            Double totalRestarts = prometheusService.getTotalContainerRestarts(promUrl, clusterName);
            metrics.setTotalRestarts(totalRestarts != null ? totalRestarts.intValue() : 0);

            // Get memory breakdown
            Map<String, Double> memoryBreakdown = prometheusService.getContainerMemoryBreakdown(promUrl, clusterName);
            metrics.setTotalMemoryWorkingSet(memoryBreakdown.getOrDefault("workingSet", 0.0));
            metrics.setTotalMemoryRss(memoryBreakdown.getOrDefault("rss", 0.0));
            metrics.setTotalMemoryAvailable(memoryBreakdown.getOrDefault("available", 0.0));

            // Get detailed container metrics
            List<Map<String, Object>> containerData = prometheusService.getDetailedContainerMetrics(promUrl,
                    clusterName);
            List<ContainerMetrics.ContainerInfo> containers = new ArrayList<>();
            for (Map<String, Object> data : containerData) {
                ContainerMetrics.ContainerInfo info = new ContainerMetrics.ContainerInfo();
                info.setId((String) data.get("id"));
                info.setName((String) data.get("name"));
                info.setPod((String) data.get("pod"));
                info.setImage((String) data.get("image"));
                info.setCpuUsage((Double) data.get("cpuUsage"));
                info.setMemoryUsage((Double) data.get("memoryUsage"));
                info.setFsUsage((Double) data.get("fsUsage"));
                info.setRunning((Boolean) data.get("running"));
                containers.add(info);
            }
            metrics.setContainers(containers);

            logger.info("✅ Built container metrics: {} running, {} waiting, {} terminated",
                    metrics.getRunningCount(), metrics.getWaitingCount(), metrics.getTerminatedCount());

        } catch (Exception e) {
            logger.error("❌ Error building container metrics", e);
        }

        return metrics;
    }

    /**
     * Build comprehensive node metrics for dedicated Nodes tab
     */
    private NodeMetrics buildNodeMetrics(String promUrl, String clusterName) {
        NodeMetrics metrics = new NodeMetrics();

        try {
            // Get node condition counts
            Map<String, Integer> conditionCounts = prometheusService.getNodeConditionCounts(promUrl, clusterName);
            metrics.setConditionsReady(conditionCounts.getOrDefault("Ready", 0));
            metrics.setConditionsDiskPressure(conditionCounts.getOrDefault("DiskPressure", 0));
            metrics.setConditionsMemoryPressure(conditionCounts.getOrDefault("MemoryPressure", 0));
            metrics.setConditionsPidPressure(conditionCounts.getOrDefault("PIDPressure", 0));

            // Get node details from existing method
            List<NodeDetail> nodeDetails = buildNodeDetails(promUrl, clusterName);

            // Calculate totals from unique nodes
            double totalCpu = 0.0;
            double totalMemory = 0.0;
            int totalPods = 0;

            // FIX: Use Set to ensure we're counting unique nodes only
            Set<String> uniqueNodeNames = new HashSet<>();
            List<NodeMetrics.NodeInfo> nodeInfoList = new ArrayList<>();
            for (NodeDetail detail : nodeDetails) {
                String nodeName = detail.getName();

                // Skip if we've already processed this node (deduplication)
                if (!uniqueNodeNames.add(nodeName)) {
                    logger.debug("Skipping duplicate node: {}", nodeName);
                    continue;
                }

                NodeMetrics.NodeInfo info = new NodeMetrics.NodeInfo();
                info.setName(nodeName);
                info.setInternalIp(detail.getInternalIp());
                info.setReady(detail.getConditions() != null && detail.getConditions().contains("Ready"));
                info.setCpuCapacity(detail.getCpuCapacity());
                info.setCpuAllocatable(detail.getCpuAllocatable());
                info.setCpuUsage(detail.getCpuUsagePercent());
                info.setCpuUsageMillicores(detail.getCpuUsageMillicores());
                info.setMemoryCapacity(detail.getMemoryCapacity());
                info.setMemoryAllocatable(detail.getMemoryAllocatable());
                info.setMemoryUsage(detail.getMemoryUsagePercent());
                info.setPodCapacity(detail.getPodsCapacity());
                info.setPodCount(detail.getPodCount());
                info.setKubeletVersion(detail.getKubeletVersion());
                info.setZone(detail.getZone());
                info.setInstanceId(detail.getInstanceId());

                totalCpu += detail.getCpuCapacity() != null ? detail.getCpuCapacity() : 0.0;
                totalMemory += detail.getMemoryCapacity() != null ? detail.getMemoryCapacity() : 0.0;
                totalPods += detail.getPodCount() != null ? detail.getPodCount() : 0;

                nodeInfoList.add(info);
            }

            // FIX: Use readyCount from Prometheus condition counts (already correct)
            // Don't recalculate from nodeDetails which might have duplicates
            metrics.setReadyCount(conditionCounts.getOrDefault("Ready", 0));
            metrics.setTotalCpuCapacity(totalCpu);
            metrics.setTotalMemoryCapacity(totalMemory);
            metrics.setTotalPods(totalPods);
            metrics.setNodes(nodeInfoList);

            logger.info("✅ Built node metrics: {} ready, {:.1f} total CPU, {:.1f} GB total memory",
                    metrics.getReadyCount(), metrics.getTotalCpuCapacity(), metrics.getTotalMemoryCapacity());

        } catch (Exception e) {
            logger.error("❌ Error building node metrics", e);
        }

        return metrics;
    }
}