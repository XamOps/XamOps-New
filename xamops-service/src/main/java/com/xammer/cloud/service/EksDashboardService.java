package com.xammer.cloud.service;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.KubernetesCluster;
import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.dto.k8s.EksCompleteDashboardDto;
import com.xammer.cloud.dto.k8s.EksCompleteDashboardDto.*;
import com.xammer.cloud.dto.k8s.KubescapeDashboard;
import com.xammer.cloud.dto.k8s.TrivyDashboard;
import com.xammer.cloud.dto.k8s.KarpenterDashboard;
import com.xammer.cloud.dto.k8s.OpenCostDashboard;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import com.xammer.cloud.repository.KubernetesClusterRepository;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesResponse;
import software.amazon.awssdk.services.ec2.model.Instance;
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
    private final OpenCostService openCostService;
    private final K8sClientFactory k8sClientFactory;
    private final RedisCacheService redisCacheService;
    private final AwsClientProvider awsClientProvider;

    public EksDashboardService(PrometheusService prometheusService,
            KubernetesClusterRepository kubernetesClusterRepository,
            KubernetesClusterConfigRepository clusterConfigRepository,
            KubescapeService kubescapeService,
            TrivyService trivyService,
            KarpenterService karpenterService,
            OpenCostService openCostService,
            K8sClientFactory k8sClientFactory,
            RedisCacheService redisCacheService,
            AwsClientProvider awsClientProvider) {
        this.prometheusService = prometheusService;
        this.kubernetesClusterRepository = kubernetesClusterRepository;
        this.clusterConfigRepository = clusterConfigRepository;
        this.kubescapeService = kubescapeService;
        this.trivyService = trivyService;
        this.karpenterService = karpenterService;
        this.openCostService = openCostService;
        this.k8sClientFactory = k8sClientFactory;
        this.redisCacheService = redisCacheService;
        this.awsClientProvider = awsClientProvider;
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
                dashboard.setNodeDetails(buildNodeDetails(account, region, promUrl, clusterName));

                // ✅ NEW: Add aggregated metrics for new tabs
                dashboard.setPodMetrics(buildPodMetrics(promUrl, clusterName));
                dashboard.setContainerMetrics(buildContainerMetrics(promUrl, clusterName));
                dashboard.setNodeMetrics(buildNodeMetrics(account, region, promUrl, clusterName));
                dashboard.setPodResourceMetrics(buildPodResourceMetrics(promUrl, clusterName));

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

                                // 4. OpenCost (Conditional - based on opencostEnabled flag)
                                if (Boolean.TRUE.equals(config.getOpencostEnabled())) {
                                    try {
                                        OpenCostDashboard ocDashboard = openCostService.fetchDashboardData(client);
                                        ocDashboard.setClusterName(clusterName);
                                        dashboard.setOpenCost(ocDashboard);
                                        logger.info(
                                                "💰 OpenCost data fetched: enabled={}, namespaces={}, dailyCost=${}",
                                                ocDashboard.isEnabled(),
                                                ocDashboard.getNamespaceAllocations().size(),
                                                String.format("%.2f", ocDashboard.getSummary().getTotalDailyCost()));

                                        // Populate namespace and workload costs from OpenCost data
                                        populateCostAllocationsFromOpenCost(dashboard);
                                    } catch (Exception openCostEx) {
                                        logger.error("❌ Error fetching OpenCost data for cluster: {}", clusterName,
                                                openCostEx);
                                    }
                                } else {
                                    logger.info("ℹ️ OpenCost is NOT enabled for cluster: {}", clusterName);
                                }

                                // 5. Pod Enrichment (Independent)
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
        overview.setMemoryUsageBytes(prometheusService.getClusterMemoryUsageBytes(promUrl, clusterName));
        overview.setMemoryCapacityBytes(prometheusService.getClusterMemoryCapacityBytes(promUrl, clusterName));

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

        // P2: Get node labels for capacity type detection
        List<Map<String, String>> nodeLabels = prometheusService.getNodeLabels(promUrl, clusterName);
        Map<String, Map<String, String>> nodeLabelMap = new HashMap<>();
        for (Map<String, String> label : nodeLabels) {
            String nodeName = label.get("node");
            if (nodeName != null) {
                nodeLabelMap.put(nodeName, label);
            }
        }

        // P2: Get node usage and capacity for cost allocation weighting
        Map<String, Map<String, Double>> nodeCapacityMap = prometheusService.getNodeCapacity(promUrl, clusterName);
        Map<String, Double> nodeCpuMillicoresMap = prometheusService.getNodeCpuUsageMillicores(promUrl, clusterName);

        List<CostMetrics.NodeCost> nodeCosts = new ArrayList<>();

        // ✅ Iterate through instance IDs and map to node names
        for (String instanceId : hourlyCostsByInstanceId.keySet()) {
            String nodeName = instanceIdToNodeName.getOrDefault(instanceId, instanceId);

            // P2: Extract capacity type, instance type, and node group from labels
            Map<String, String> labels = nodeLabelMap.get(nodeName);
            String capacityType = "on-demand"; // Default
            String instanceType = "Unknown"; // Default - will be updated from labels
            String nodeGroupName = null;

            if (labels != null) {
                // Get instance type from node labels
                if (labels.containsKey("label_node_kubernetes_io_instance_type")) {
                    instanceType = labels.get("label_node_kubernetes_io_instance_type");
                }

                // Check EKS managed node group labels
                if (labels.containsKey("label_eks_amazonaws_com_capacityType")) {
                    capacityType = labels.get("label_eks_amazonaws_com_capacityType").toLowerCase();
                }
                // Check Karpenter labels
                else if (labels.containsKey("label_karpenter_sh_capacity_type")) {
                    capacityType = labels.get("label_karpenter_sh_capacity_type").toLowerCase();
                }

                // Get node group name
                if (labels.containsKey("label_eks_amazonaws_com_nodegroup")) {
                    nodeGroupName = labels.get("label_eks_amazonaws_com_nodegroup");
                } else if (labels.containsKey("label_karpenter_sh_nodepool")) {
                    nodeGroupName = labels.get("label_karpenter_sh_nodepool");
                }
            }

            CostMetrics.NodeCost nc = new CostMetrics.NodeCost(
                    nodeName,
                    hourlyCostsByInstanceId.get(instanceId),
                    dailyCostsByInstanceId.getOrDefault(instanceId, 0.0),
                    instanceType,
                    capacityType,
                    nodeGroupName);

            // Calculate CPU Usage Percent
            double cpuUsed = nodeCpuMillicoresMap.getOrDefault(nodeName, 0.0);
            double cpuCap = nodeCapacityMap.getOrDefault(nodeName, new HashMap<>()).getOrDefault("cpu", 0.0);
            if (cpuCap > 0) {
                nc.setCpuUsagePercent((cpuUsed / (cpuCap * 1000)) * 100);
            } else {
                nc.setCpuUsagePercent(0.0);
            }
            nodeCosts.add(nc);
        }

        cost.setNodeCosts(nodeCosts);

        // P2: Build namespace cost allocation
        cost.setNamespaceCosts(buildNamespaceCostAllocation(promUrl, clusterName, dailyCost));

        // P2: Build workload cost allocation
        cost.setWorkloadCosts(buildWorkloadCostAllocation(promUrl, clusterName, dailyCost));

        return cost;
    }

    /**
     * P2: Calculate cost allocation per namespace based on resource usage
     * Cost is allocated proportionally based on CPU + Memory usage ratio
     */
    private List<CostMetrics.NamespaceCostAllocation> buildNamespaceCostAllocation(
            String promUrl, String clusterName, Double totalDailyCost) {

        List<CostMetrics.NamespaceCostAllocation> allocations = new ArrayList<>();

        try {
            Map<String, Double> namespaceCpu = prometheusService.getNamespaceCpuUsage(promUrl, clusterName);
            Map<String, Double> namespaceMem = prometheusService.getNamespaceMemoryUsage(promUrl, clusterName);
            Map<String, Double> namespacePodCount = prometheusService.getNamespacePodCount(promUrl, clusterName);

            // Calculate total resource usage for proportional allocation
            double totalCpu = namespaceCpu.values().stream().mapToDouble(Double::doubleValue).sum();
            double totalMem = namespaceMem.values().stream().mapToDouble(Double::doubleValue).sum();

            Set<String> allNamespaces = new HashSet<>();
            allNamespaces.addAll(namespaceCpu.keySet());
            allNamespaces.addAll(namespaceMem.keySet());

            for (String namespace : allNamespaces) {
                CostMetrics.NamespaceCostAllocation alloc = new CostMetrics.NamespaceCostAllocation();
                alloc.setNamespace(namespace);

                double cpuUsage = namespaceCpu.getOrDefault(namespace, 0.0);
                double memUsage = namespaceMem.getOrDefault(namespace, 0.0);
                int podCount = namespacePodCount.getOrDefault(namespace, 0.0).intValue();

                alloc.setCpuUsageMillicores(cpuUsage);
                alloc.setMemoryUsageMb(memUsage);
                alloc.setPodCount(podCount);

                // Calculate cost percentage (weighted average of CPU and Memory ratio)
                double cpuRatio = totalCpu > 0 ? cpuUsage / totalCpu : 0;
                double memRatio = totalMem > 0 ? memUsage / totalMem : 0;
                double costPercentage = (cpuRatio + memRatio) / 2 * 100;
                alloc.setCostPercentage(Math.round(costPercentage * 100.0) / 100.0);

                // Calculate daily and monthly cost
                double nsDailyCost = totalDailyCost != null ? totalDailyCost * (costPercentage / 100) : 0;
                alloc.setDailyCost(Math.round(nsDailyCost * 100.0) / 100.0);
                alloc.setMonthlyCost(Math.round(nsDailyCost * 30 * 100.0) / 100.0);

                allocations.add(alloc);
            }

            // Sort by daily cost descending
            allocations.sort((a, b) -> Double.compare(b.getDailyCost(), a.getDailyCost()));

        } catch (Exception e) {
            logger.error("Error building namespace cost allocation: {}", e.getMessage());
        }

        return allocations;
    }

    /**
     * P2: Calculate cost allocation per workload (deployment/daemonset) based on
     * resource usage
     */
    private List<CostMetrics.WorkloadCostAllocation> buildWorkloadCostAllocation(
            String promUrl, String clusterName, Double totalDailyCost) {

        List<CostMetrics.WorkloadCostAllocation> allocations = new ArrayList<>();

        try {
            // Get all workload CPU and Memory usage using robust kube_pod_owner joins
            List<Map<String, String>> cpuResults = prometheusService.getWorkloadCpuUsage(promUrl, clusterName);
            List<Map<String, String>> memResults = prometheusService.getWorkloadMemoryUsage(promUrl, clusterName);

            // Fetch replication info for pod counts and scaling status
            Map<String, Double> deploymentReplicas = prometheusService.getDeploymentReplicasDesired(promUrl,
                    clusterName);
            Map<String, Double> deploymentAvailable = prometheusService.getDeploymentReplicasAvailable(promUrl,
                    clusterName);

            // Group metrics by a composite key of namespace/kind/name
            Map<String, CostMetrics.WorkloadCostAllocation> workloadMap = new HashMap<>();

            // Process CPU metrics
            for (Map<String, String> row : cpuResults) {
                String name = row.get("owner_name");
                String kind = row.get("owner_kind");
                String namespace = row.get("namespace");
                String valStr = row.getOrDefault("value", "0");

                if (name == null || kind == null || namespace == null)
                    continue;

                double cpu = 0;
                try {
                    cpu = Double.parseDouble(valStr);
                } catch (Exception e) {
                    logger.warn("Invalid CPU value: {}", valStr);
                }

                String key = namespace + "/" + kind + "/" + name;
                CostMetrics.WorkloadCostAllocation alloc = workloadMap.computeIfAbsent(key, k -> {
                    CostMetrics.WorkloadCostAllocation a = new CostMetrics.WorkloadCostAllocation();
                    a.setName(name);
                    a.setKind(kind);
                    a.setNamespace(namespace);
                    a.setCpuUsageMillicores(0.0);
                    a.setMemoryUsageMb(0.0);
                    return a;
                });
                alloc.setCpuUsageMillicores(cpu);
            }

            // Process Memory metrics
            for (Map<String, String> row : memResults) {
                String name = row.get("owner_name");
                String kind = row.get("owner_kind");
                String namespace = row.get("namespace");
                String valStr = row.getOrDefault("value", "0");

                if (name == null || kind == null || namespace == null)
                    continue;

                double mem = 0;
                try {
                    mem = Double.parseDouble(valStr);
                } catch (Exception e) {
                    logger.warn("Invalid Memory value: {}", valStr);
                }

                String key = namespace + "/" + kind + "/" + name;
                CostMetrics.WorkloadCostAllocation alloc = workloadMap.computeIfAbsent(key, k -> {
                    CostMetrics.WorkloadCostAllocation a = new CostMetrics.WorkloadCostAllocation();
                    a.setName(name);
                    a.setKind(kind);
                    a.setNamespace(namespace);
                    a.setCpuUsageMillicores(0.0);
                    a.setMemoryUsageMb(0.0);
                    return a;
                });
                alloc.setMemoryUsageMb(mem);
            }

            // Calculate totals for ratio-based cost distribution - use null-safe double
            // unboxing
            double totalCpu = workloadMap.values().stream()
                    .filter(a -> a.getCpuUsageMillicores() != null)
                    .mapToDouble(CostMetrics.WorkloadCostAllocation::getCpuUsageMillicores)
                    .sum();
            double totalMem = workloadMap.values().stream()
                    .filter(a -> a.getMemoryUsageMb() != null)
                    .mapToDouble(CostMetrics.WorkloadCostAllocation::getMemoryUsageMb)
                    .sum();

            // Enrich with pod counts/replicas and calculate costs
            for (CostMetrics.WorkloadCostAllocation alloc : workloadMap.values()) {
                String name = alloc.getName();
                String namespace = alloc.getNamespace();
                String deployKey = namespace + "/" + name;

                if ("Deployment".equalsIgnoreCase(alloc.getKind())) {
                    if (deploymentReplicas != null) {
                        alloc.setReplicas(deploymentReplicas.getOrDefault(deployKey, 0.0).intValue());
                    }
                    if (deploymentAvailable != null) {
                        alloc.setPodCount(deploymentAvailable.getOrDefault(deployKey, 0.0).intValue());
                    }
                }

                // Weighted cost allocation: 50% CPU, 50% Memory ratio
                double cpuUsage = alloc.getCpuUsageMillicores() != null ? alloc.getCpuUsageMillicores() : 0.0;
                double memUsage = alloc.getMemoryUsageMb() != null ? alloc.getMemoryUsageMb() : 0.0;

                double cpuRatio = totalCpu > 0 ? cpuUsage / totalCpu : 0;
                double memRatio = totalMem > 0 ? memUsage / totalMem : 0;
                double costFraction = (cpuRatio + memRatio) / 2.0;

                double dailyCost = totalDailyCost != null ? totalDailyCost * costFraction : 0;
                alloc.setDailyCost(Math.round(dailyCost * 100.0) / 100.0);
                alloc.setMonthlyCost(Math.round(dailyCost * 30 * 100.0) / 100.0);

                if (alloc.getDailyCost() > 0 || cpuUsage > 0) {
                    allocations.add(alloc);
                }
            }

            // Sort by daily cost descending
            allocations.sort((a, b) -> Double.compare(
                    b.getDailyCost() != null ? b.getDailyCost() : 0.0,
                    a.getDailyCost() != null ? a.getDailyCost() : 0.0));

            // Limit results to preserve performance
            if (allocations.size() > 60) {
                allocations = new ArrayList<>(allocations.subList(0, 60));
            }

        } catch (Exception e) {
            logger.error("Error building workload cost allocation with owner-joins: {}", e.getMessage(), e);
        }

        return allocations;
    }

    /**
     * Populate namespace and workload cost allocations from OpenCost data
     * This should be called AFTER OpenCost data is fetched
     */
    private void populateCostAllocationsFromOpenCost(EksCompleteDashboardDto dashboard) {
        if (dashboard == null || dashboard.getCostMetrics() == null) {
            return;
        }

        OpenCostDashboard openCost = dashboard.getOpenCost();
        if (openCost == null || !openCost.isEnabled()) {
            logger.debug("OpenCost data not available, keeping Prometheus-based cost allocations");
            return;
        }

        CostMetrics costMetrics = dashboard.getCostMetrics();

        try {
            // Convert OpenCost NamespaceAllocations to CostMetrics.NamespaceCostAllocation
            List<CostMetrics.NamespaceCostAllocation> namespaceCosts = new ArrayList<>();
            for (OpenCostDashboard.NamespaceAllocation na : openCost.getNamespaceAllocations()) {
                if (na.getNamespace() == null || na.getNamespace().contains("__idle__")
                        || na.getNamespace().contains("__unallocated__")) {
                    continue;
                }

                CostMetrics.NamespaceCostAllocation alloc = new CostMetrics.NamespaceCostAllocation();
                alloc.setNamespace(na.getNamespace());

                // Convert costs from OpenCost data window to daily cost
                double totalCost = na.getTotalCost() != null ? na.getTotalCost() : 0.0;
                double minutes = na.getMinutes() != null ? na.getMinutes() : 0.0;
                double dailyCost = totalCost;
                if (minutes > 0 && minutes < 1440) { // If less than 24h, extrapolate
                    dailyCost = (totalCost / minutes) * 1440; // Convert to 24h
                }

                alloc.setDailyCost(Math.round(dailyCost * 100.0) / 100.0);
                alloc.setMonthlyCost(Math.round(dailyCost * 30 * 100.0) / 100.0);

                // CPU and Memory usage (convert from cores/bytes to millicores/MB)
                double cpuCores = na.getCpuCoreUsageAverage() != null ? na.getCpuCoreUsageAverage() : 0.0;
                double ramBytes = na.getRamByteUsageAverage() != null ? na.getRamByteUsageAverage() : 0.0;

                alloc.setCpuUsageMillicores(cpuCores * 1000); // cores to millicores
                alloc.setMemoryUsageMb(ramBytes / (1024 * 1024)); // bytes to MB

                // Pod count (estimate if not available)
                alloc.setPodCount(0); // OpenCost namespace data doesn't have pod count

                // Cost percentage
                alloc.setCostPercentage(na.getCostPercentage() != null ? na.getCostPercentage() : 0.0);

                namespaceCosts.add(alloc);
            }

            // Sort by daily cost descending
            namespaceCosts.sort((a, b) -> Double.compare(b.getDailyCost(), a.getDailyCost()));
            costMetrics.setNamespaceCosts(namespaceCosts);
            logger.info("✅ Populated {} namespace cost allocations from OpenCost", namespaceCosts.size());

            // Convert OpenCost ControllerAllocations to CostMetrics.WorkloadCostAllocation
            List<CostMetrics.WorkloadCostAllocation> workloadCosts = new ArrayList<>();
            for (OpenCostDashboard.ControllerAllocation ca : openCost.getControllerAllocations()) {
                if (ca.getController() == null || ca.getController().contains("__idle__")
                        || ca.getController().contains("__unallocated__")) {
                    continue;
                }

                CostMetrics.WorkloadCostAllocation alloc = new CostMetrics.WorkloadCostAllocation();
                alloc.setName(ca.getController());
                alloc.setNamespace(ca.getNamespace() != null ? ca.getNamespace() : "default");
                alloc.setKind(ca.getControllerKind() != null ? ca.getControllerKind() : "Unknown");

                // Convert costs from OpenCost data window to daily cost
                double totalCost = ca.getTotalCost() != null ? ca.getTotalCost() : 0.0;
                double minutes = ca.getMinutes() != null ? ca.getMinutes() : 0.0;
                double dailyCost = totalCost;
                if (minutes > 0 && minutes < 1440) { // If less than 24h, extrapolate
                    dailyCost = (totalCost / minutes) * 1440; // Convert to 24h
                }

                alloc.setDailyCost(Math.round(dailyCost * 100.0) / 100.0);
                alloc.setMonthlyCost(Math.round(dailyCost * 30 * 100.0) / 100.0);

                // CPU and Memory usage (convert from cores/bytes to millicores/MB)
                double cpuCores = ca.getCpuCoreUsageAverage() != null ? ca.getCpuCoreUsageAverage() : 0.0;
                double ramBytes = ca.getRamByteUsageAverage() != null ? ca.getRamByteUsageAverage() : 0.0;

                alloc.setCpuUsageMillicores(cpuCores * 1000); // cores to millicores
                alloc.setMemoryUsageMb(ramBytes / (1024 * 1024)); // bytes to MB

                // Pod count and replicas
                alloc.setPodCount(ca.getPodCount() != null ? ca.getPodCount() : 0);
                alloc.setReplicas(null); // Not available in OpenCost data

                workloadCosts.add(alloc);
            }

            // Sort by daily cost descending
            workloadCosts.sort((a, b) -> Double.compare(b.getDailyCost(), a.getDailyCost()));
            costMetrics.setWorkloadCosts(workloadCosts);
            logger.info("✅ Populated {} workload cost allocations from OpenCost", workloadCosts.size());

        } catch (Exception e) {
            logger.error("Error populating cost allocations from OpenCost: {}", e.getMessage(), e);
        }
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
        Map<String, Double> nodeCpuMillicores = prometheusService.getNodeCpuUsageMillicores(promUrl, clusterName);
        Map<String, Double> nodeMemUsageBytes = prometheusService.getNodeMemoryUsageBytes(promUrl, clusterName);
        Map<String, Double> nodeCpuRequests = prometheusService.getNodeCpuRequests(promUrl, clusterName);
        Map<String, Double> nodeMemRequests = prometheusService.getNodeMemoryRequests(promUrl, clusterName);
        Map<String, String> nodeCapacityTypes = prometheusService.getNodeCapacityTypes(promUrl, clusterName);

        // Get node labels to extract instance type and other metadata
        List<Map<String, String>> nodeLabels = prometheusService.getNodeLabels(promUrl, clusterName);
        Map<String, Map<String, String>> nodeLabelMap = new HashMap<>();
        for (Map<String, String> label : nodeLabels) {
            String nodeName = label.get("node");
            if (nodeName != null) {
                nodeLabelMap.put(nodeName, label);
            }
        }

        logger.info("🔍 Node capacity keys: {}", nodeCapacity.keySet());
        logger.info("🔍 Node allocatable keys: {}", nodeAllocatable.keySet());
        logger.info("🔍 Node conditions keys: {}", nodeConditions.keySet());
        logger.info("🔍 Node costs (by instanceId) keys: {}", nodeCostsByInstanceId.keySet());
        logger.info("🔍 Node labels: {} nodes found", nodeLabelMap.size());

        List<ResourceUtilization.NodeResource> nodes = new ArrayList<>();

        for (Map<String, String> nodeInfo : nodeInfoList) {
            String nodeName = nodeInfo.get("node");
            String providerId = nodeInfo.get("provider_id");

            logger.info("🔍 Processing node: {}, providerId: {}", nodeName, providerId);

            String condition = nodeConditions.getOrDefault(nodeName, "Unknown");
            // Allow all nodes to be returned; frontend will handle filtering
            logger.debug("Node {} has condition: {}", nodeName, condition);

            String instanceId = null;
            if (providerId != null && providerId.contains("/i-")) {
                instanceId = providerId.substring(providerId.lastIndexOf("/") + 1);
                logger.info("🔍 Extracted instanceId: {}", instanceId);
            }

            // Extract instance type from node labels
            String instanceType = "Unknown";
            Map<String, String> labels = nodeLabelMap.get(nodeName);
            if (labels != null && labels.containsKey("label_node_kubernetes_io_instance_type")) {
                instanceType = labels.get("label_node_kubernetes_io_instance_type");
                logger.info("✅ Extracted instance type for node {}: {}", nodeName, instanceType);
            } else {
                logger.warn("⚠️ Instance type label not found for node: {}", nodeName);
            }

            ResourceUtilization.NodeResource node = new ResourceUtilization.NodeResource();
            node.setNodeName(nodeName);
            node.setInstanceType(instanceType);
            node.setZone(extractZone(providerId));
            node.setCondition(condition);

            // Fetch capacity
            Map<String, Double> capacity = nodeCapacity.getOrDefault(nodeName, new HashMap<>());
            double cpuCap = capacity.getOrDefault("cpu", 0.0); // in cores
            double memCap = capacity.getOrDefault("memory", 0.0); // in GB
            node.setCpuCapacity(cpuCap);
            node.setMemoryCapacity(memCap);

            // Fetch allocatable
            Map<String, Double> allocatable = nodeAllocatable.getOrDefault(nodeName, new HashMap<>());
            node.setCpuAllocatable(allocatable.getOrDefault("cpu", 0.0));
            node.setMemoryAllocatable(allocatable.getOrDefault("memory", 0.0));
            node.setPodsCapacity(capacity.getOrDefault("pods", 0.0));
            node.setPodsAllocatable(allocatable.getOrDefault("pods", 0.0));
            node.setHourlyCost(nodeCostsByInstanceId.getOrDefault(instanceId, 0.0));

            // Set usage from millicores/bytes and calculate percentage
            double cpuUsed = nodeCpuMillicores.getOrDefault(nodeName, 0.0); // in millicores
            node.setCpuUsageMillicores(cpuUsed);
            if (cpuCap > 0) {
                node.setCpuUsagePercent((cpuUsed / (cpuCap * 1000)) * 100);
            } else {
                node.setCpuUsagePercent(0.0);
            }

            double memUsedBytes = nodeMemUsageBytes.getOrDefault(nodeName, 0.0); // in bytes
            node.setMemoryUsageMB(memUsedBytes / (1024 * 1024)); // bytes to MB
            if (memCap > 0) {
                double memCapBytes = memCap * 1024 * 1024 * 1024; // GB to bytes
                node.setMemoryUsagePercent((memUsedBytes / memCapBytes) * 100);
            } else {
                node.setMemoryUsagePercent(0.0);
            }

            double cpuReq = nodeCpuRequests.getOrDefault(nodeName, 0.0);
            node.setCpuRequests(cpuReq);
            if (cpuCap > 0) {
                node.setCpuRequestsPercent((cpuReq / (cpuCap * 1000)) * 100);
            } else {
                node.setCpuRequestsPercent(0.0);
            }

            double memReq = nodeMemRequests.getOrDefault(nodeName, 0.0);
            node.setMemoryRequests(memReq / (1024 * 1024)); // bytes to MB
            if (memCap > 0) {
                double memCapBytes = memCap * 1024 * 1024 * 1024;
                node.setMemoryRequestsPercent((memReq / memCapBytes) * 100);
            } else {
                node.setMemoryRequestsPercent(0.0);
            }

            // Set Capacity Type
            String capType = nodeCapacityTypes.getOrDefault(nodeName, "on-demand");
            if (capType == null && labels != null) {
                capType = labels.get("label_eks_amazonaws_com_capacityType");
                if (capType == null)
                    capType = labels.get("label_karpenter_sh_capacity_type");
            }
            node.setCapacityType(capType != null ? capType : "on-demand");

            nodes.add(node);
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
        workload.setStatefulsets(buildStatefulsetHealth(promUrl, clusterName));

        // ✅ Calculate Cluster Health Score
        // Formula: (Healthy Resources / Total Resources) * 100
        double totalResources = 0;
        double healthyResources = 0;

        // 1. Pods
        if (workload.getPods() != null) {
            totalResources += workload.getPods().size();
            healthyResources += workload.getPods().stream()
                    .filter(p -> Boolean.TRUE.equals(p.getReady()) && !"Failed".equals(p.getPhase()))
                    .count();
        }

        // 2. Deployments
        if (workload.getDeployments() != null) {
            totalResources += workload.getDeployments().size();
            healthyResources += workload.getDeployments().stream()
                    .filter(d -> d.getUnavailable() == null || d.getUnavailable() == 0)
                    .count();
        }

        // 3. DaemonSets
        if (workload.getDaemonsets() != null) {
            totalResources += workload.getDaemonsets().size();
            healthyResources += workload.getDaemonsets().stream()
                    .filter(ds -> ds.getUnavailable() == null || ds.getUnavailable() == 0)
                    .count();
        }

        // 4. StatefulSets
        if (workload.getStatefulsets() != null) {
            totalResources += workload.getStatefulsets().size();
            healthyResources += workload.getStatefulsets().stream()
                    .filter(ss -> ss.getReady() != null && ss.getDesired() != null && ss.getReady() >= ss.getDesired())
                    .count();
        }

        if (totalResources > 0) {
            workload.setClusterHealthScore((healthyResources / totalResources) * 100.0);
        } else {
            workload.setClusterHealthScore(100.0); // Default to 100 if no resources (fresh cluster)
        }

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
        Map<String, Map<String, String>> podOwners = prometheusService.getPodOwner(promUrl, clusterName);
        Map<String, Double> podRestarts = prometheusService.getPodRestarts(promUrl, clusterName);
        Map<String, Double> podCreationTimes = prometheusService.getPodCreationTime(promUrl, clusterName);

        // Use container-based pod usage for consistency with container metrics
        Map<String, Double> podCpu = prometheusService.getPodCpuUsageFromContainers(promUrl, clusterName);
        Map<String, Double> podMem = prometheusService.getPodMemoryUsage(promUrl, clusterName);

        // Requests and Limits
        Map<String, Double> podCpuRequests = prometheusService.getPodCpuRequests(promUrl, clusterName);
        Map<String, Double> podMemRequests = prometheusService.getPodMemoryRequests(promUrl, clusterName);
        Map<String, Double> podCpuLimits = prometheusService.getPodCpuLimits(promUrl, clusterName);
        Map<String, Double> podMemLimits = prometheusService.getPodMemoryLimits(promUrl, clusterName);

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
            pod.setRestarts(podRestarts.getOrDefault(podName, 0.0).intValue());
            pod.setCpuUsage(podCpu.getOrDefault(podName, 0.0));
            pod.setMemoryUsage(podMem.getOrDefault(podName, 0.0));

            pod.setPodIp(podInfo.get("pod_ip"));

            // Convert creation timestamp to age string
            Double creationTimestamp = podCreationTimes.get(podName);
            if (creationTimestamp != null) {
                long ageSeconds = (long) (System.currentTimeMillis() / 1000.0 - creationTimestamp);
                pod.setAge(formatAge(ageSeconds));
            } else {
                pod.setAge("N/A");
            }

            // Owner information
            Map<String, String> owner = podOwners.get(podName);
            if (owner != null) {
                pod.setOwnerKind(owner.get("kind"));
                pod.setOwnerName(owner.get("name"));
            }

            // Requests and limits
            pod.setCpuRequest(podCpuRequests.getOrDefault(podName, 0.0));
            pod.setMemoryRequest(podMemRequests.getOrDefault(podName, 0.0));
            pod.setCpuLimit(podCpuLimits.getOrDefault(podName, 0.0));
            pod.setMemoryLimit(podMemLimits.getOrDefault(podName, 0.0));

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

        // Get deployment info with namespaces for lookup
        List<Map<String, String>> deploymentInfo = prometheusService.getClusterDeployments(promUrl, clusterName);
        Map<String, String> deploymentNamespaces = new HashMap<>();
        for (Map<String, String> info : deploymentInfo) {
            String name = info.get("deployment");
            String ns = info.get("namespace");
            if (name != null && ns != null) {
                deploymentNamespaces.put(name, ns);
            }
        }

        // P1: Get resource usage for deployments
        Map<String, Map<String, Double>> deploymentCpu = prometheusService.getDeploymentCpuUsage(promUrl, clusterName);
        Map<String, Map<String, Double>> deploymentMem = prometheusService.getDeploymentMemoryUsage(promUrl,
                clusterName);

        List<WorkloadHealth.DeploymentHealth> deployments = new ArrayList<>();

        // Iterate over desired deployments (original working approach)
        for (String deploymentName : desired.keySet()) {
            String namespace = deploymentNamespaces.getOrDefault(deploymentName, "default");

            WorkloadHealth.DeploymentHealth deployment = new WorkloadHealth.DeploymentHealth();
            deployment.setName(deploymentName);
            deployment.setNamespace(namespace);

            int desiredCount = desired.getOrDefault(deploymentName, 0.0).intValue();
            int availableCount = available.getOrDefault(deploymentName, 0.0).intValue();

            deployment.setDesired(desiredCount);
            deployment.setAvailable(availableCount);
            deployment.setUnavailable(Math.max(0, desiredCount - availableCount));
            deployment.setUpdated(availableCount);

            // P1: Set replica counts
            deployment.setReplicas(desiredCount);
            deployment.setReadyReplicas(availableCount);

            // P1: Set resource usage - try multiple key formats
            String key = namespace + "/" + deploymentName;
            Map<String, Double> cpuMetrics = deploymentCpu.getOrDefault(key, new HashMap<>());
            Map<String, Double> memMetrics = deploymentMem.getOrDefault(key, new HashMap<>());

            // If not found, try with ReplicaSet suffix pattern
            if (cpuMetrics.isEmpty()) {
                for (String k : deploymentCpu.keySet()) {
                    if (k.startsWith(namespace + "/") && k.contains(deploymentName)) {
                        cpuMetrics = deploymentCpu.get(k);
                        break;
                    }
                }
            }
            if (memMetrics.isEmpty()) {
                for (String k : deploymentMem.keySet()) {
                    if (k.startsWith(namespace + "/") && k.contains(deploymentName)) {
                        memMetrics = deploymentMem.get(k);
                        break;
                    }
                }
            }

            deployment.setCpuUsageMillicores(cpuMetrics.getOrDefault("cpu", 0.0));
            deployment.setMemoryUsageMb(memMetrics.getOrDefault("memory", 0.0));

            // Fix: Health score should be 0 when no replicas are desired (scaled to 0)
            // and 100 only when all desired replicas are available
            double healthScore;
            if (desiredCount == 0) {
                // Scaled to 0 intentionally - consider it healthy but inactive
                healthScore = availableCount == 0 ? 100.0 : 0.0;
            } else {
                healthScore = (availableCount * 100.0 / desiredCount);
            }
            deployment.setHealthScore(healthScore);

            deployments.add(deployment);
        }

        return deployments;
    }

    private List<WorkloadHealth.DaemonsetHealth> buildDaemonsetHealth(String promUrl, String clusterName) {
        Map<String, Double> desired = prometheusService.getDaemonsetDesired(promUrl, clusterName);
        Map<String, Double> ready = prometheusService.getDaemonsetReady(promUrl, clusterName);

        // Get daemonset info with namespaces for lookup
        List<Map<String, String>> daemonsetInfo = prometheusService.getClusterDaemonsets(promUrl, clusterName);
        Map<String, String> daemonsetNamespaces = new HashMap<>();
        for (Map<String, String> info : daemonsetInfo) {
            String name = info.get("daemonset");
            String ns = info.get("namespace");
            if (name != null && ns != null) {
                daemonsetNamespaces.put(name, ns);
            }
        }

        // P1: Get resource usage for daemonsets
        Map<String, Map<String, Double>> daemonsetCpu = prometheusService.getDeploymentCpuUsage(promUrl, clusterName);
        Map<String, Map<String, Double>> daemonsetMem = prometheusService.getDeploymentMemoryUsage(promUrl,
                clusterName);

        List<WorkloadHealth.DaemonsetHealth> daemonsets = new ArrayList<>();

        // Iterate over desired daemonsets (original working approach)
        for (String daemonsetName : desired.keySet()) {
            String namespace = daemonsetNamespaces.getOrDefault(daemonsetName, "kube-system");

            WorkloadHealth.DaemonsetHealth daemonset = new WorkloadHealth.DaemonsetHealth();
            daemonset.setName(daemonsetName);
            daemonset.setNamespace(namespace);

            int desiredCount = desired.getOrDefault(daemonsetName, 0.0).intValue();
            int readyCount = ready.getOrDefault(daemonsetName, 0.0).intValue();

            daemonset.setDesired(desiredCount);
            daemonset.setReady(readyCount);
            daemonset.setUnavailable(Math.max(0, desiredCount - readyCount));

            // P1: Set scheduling counts
            daemonset.setCurrentNumberScheduled(desiredCount);
            daemonset.setNumberReady(readyCount);

            // P1: Set resource usage - try multiple key formats
            String key = namespace + "/" + daemonsetName;
            Map<String, Double> cpuMetrics = daemonsetCpu.getOrDefault(key, new HashMap<>());
            Map<String, Double> memMetrics = daemonsetMem.getOrDefault(key, new HashMap<>());

            // If not found, try to match by partial name
            if (cpuMetrics.isEmpty()) {
                for (String k : daemonsetCpu.keySet()) {
                    if (k.startsWith(namespace + "/") && k.contains(daemonsetName)) {
                        cpuMetrics = daemonsetCpu.get(k);
                        break;
                    }
                }
            }
            if (memMetrics.isEmpty()) {
                for (String k : daemonsetMem.keySet()) {
                    if (k.startsWith(namespace + "/") && k.contains(daemonsetName)) {
                        memMetrics = daemonsetMem.get(k);
                        break;
                    }
                }
            }

            daemonset.setCpuUsageMillicores(cpuMetrics.getOrDefault("cpu", 0.0));
            daemonset.setMemoryUsageMb(memMetrics.getOrDefault("memory", 0.0));

            // Fix: Health score calculation
            double healthScore;
            if (desiredCount == 0) {
                // No nodes need this daemonset - consider healthy
                healthScore = readyCount == 0 ? 100.0 : 0.0;
            } else {
                healthScore = (readyCount * 100.0 / desiredCount);
            }
            daemonset.setHealthScore(healthScore);

            daemonsets.add(daemonset);
        }

        return daemonsets;
    }

    private List<WorkloadHealth.StatefulSetHealth> buildStatefulsetHealth(String promUrl, String clusterName) {
        Map<String, Double> desired = prometheusService.getStatefulsetReplicasDesired(promUrl, clusterName);
        Map<String, Double> ready = prometheusService.getStatefulsetReplicasReady(promUrl, clusterName);

        // Get statefulset info with namespaces for lookup
        List<Map<String, String>> statefulsetInfo = prometheusService.getClusterStatefulsets(promUrl, clusterName);
        Map<String, String> statefulsetNamespaces = new HashMap<>();
        for (Map<String, String> info : statefulsetInfo) {
            String name = info.get("statefulset");
            String ns = info.get("namespace");
            if (name != null && ns != null) {
                statefulsetNamespaces.put(name, ns);
            }
        }

        // Get resource usage for statefulsets (reuse deployment logic as it aggregates
        // from pods by name)
        Map<String, Map<String, Double>> statefulsetCpu = prometheusService.getDeploymentCpuUsage(promUrl, clusterName);
        Map<String, Map<String, Double>> statefulsetMem = prometheusService.getDeploymentMemoryUsage(promUrl,
                clusterName);

        List<WorkloadHealth.StatefulSetHealth> statefulsets = new ArrayList<>();

        // Iterate over desired statefulsets
        for (String statefulsetName : desired.keySet()) {
            String namespace = statefulsetNamespaces.getOrDefault(statefulsetName, "default");

            WorkloadHealth.StatefulSetHealth statefulset = new WorkloadHealth.StatefulSetHealth();
            statefulset.setName(statefulsetName);
            statefulset.setNamespace(namespace);

            int desiredCount = desired.getOrDefault(statefulsetName, 0.0).intValue();
            int readyCount = ready.getOrDefault(statefulsetName, 0.0).intValue();

            statefulset.setDesired(desiredCount);
            statefulset.setReady(readyCount);
            statefulset.setUpdated(readyCount); // Simplification, same as deployment current

            // Set replica counts
            statefulset.setReplicas(desiredCount);
            statefulset.setReadyReplicas(readyCount);

            // Set resource usage - try multiple key formats
            String key = namespace + "/" + statefulsetName;
            Map<String, Double> cpuMetrics = statefulsetCpu.getOrDefault(key, new HashMap<>());
            Map<String, Double> memMetrics = statefulsetMem.getOrDefault(key, new HashMap<>());

            // If not found, try with partial match (StatefulSets usually don't have RS
            // suffix but have ordinal index)
            if (cpuMetrics.isEmpty()) {
                for (String k : statefulsetCpu.keySet()) {
                    if (k.startsWith(namespace + "/") && k.contains(statefulsetName)) {
                        cpuMetrics = statefulsetCpu.get(k);
                        break;
                    }
                }
            }
            if (memMetrics.isEmpty()) {
                for (String k : statefulsetMem.keySet()) {
                    if (k.startsWith(namespace + "/") && k.contains(statefulsetName)) {
                        memMetrics = statefulsetMem.get(k);
                        break;
                    }
                }
            }

            statefulset.setCpuUsageMillicores(cpuMetrics.getOrDefault("cpu", 0.0));
            statefulset.setMemoryUsageMb(memMetrics.getOrDefault("memory", 0.0));

            // Health score calculation
            double healthScore;
            if (desiredCount == 0) {
                healthScore = readyCount == 0 ? 100.0 : 0.0;
            } else {
                healthScore = (readyCount * 100.0 / desiredCount);
            }
            statefulset.setHealthScore(healthScore);

            statefulsets.add(statefulset);
        }

        return statefulsets;
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
    private List<EksCompleteDashboardDto.NodeDetail> buildNodeDetails(CloudAccount account, String region,
            String promUrl, String clusterName) {
        logger.info("🔍 === BUILDING COMPREHENSIVE NODE DETAILS ===");

        List<Map<String, String>> nodeInfoList = prometheusService.getNodeInfo(promUrl, clusterName);
        Map<String, Map<String, Double>> nodeCapacity = prometheusService.getNodeCapacity(promUrl, clusterName);
        Map<String, Map<String, Double>> nodeAllocatable = prometheusService.getNodeAllocatable(promUrl, clusterName);
        Map<String, String> nodeConditions = prometheusService.getNodeConditions(promUrl, clusterName);
        Map<String, Double> nodeCpuMillicores = prometheusService.getNodeCpuUsageMillicores(promUrl, clusterName);
        Map<String, Double> nodeMemUsageBytes = prometheusService.getNodeMemoryUsageBytes(promUrl, clusterName);
        Map<String, Double> nodeCpuRequests = prometheusService.getNodeCpuRequests(promUrl, clusterName);
        Map<String, Double> nodeMemRequests = prometheusService.getNodeMemoryRequests(promUrl, clusterName);

        // ✅ NEW: Get all node conditions (Ready, DiskPressure, MemoryPressure,
        // PIDPressure)
        Map<String, List<String>> allNodeConditions = prometheusService.getNodeAllConditions(promUrl, clusterName);

        // ✅ NEW: Get enhanced node data
        Map<String, String> nodeCapacityTypes = prometheusService.getNodeCapacityTypes(promUrl, clusterName);
        Map<String, String> nodeGroups = prometheusService.getNodeGroups(promUrl, clusterName);
        Map<String, Boolean> nodePIDPressure = prometheusService.getNodePIDPressure(promUrl, clusterName);

        // Get node labels for instance type extraction
        List<Map<String, String>> nodeLabels = prometheusService.getNodeLabels(promUrl, clusterName);
        Map<String, Map<String, String>> nodeLabelMap = new HashMap<>();
        for (Map<String, String> label : nodeLabels) {
            String nodeName = label.get("node");
            if (nodeName != null) {
                nodeLabelMap.put(nodeName, label);
            }
        }

        // ✅ NEW: Get pods per node
        List<Map<String, String>> podInfoList = prometheusService.getPodInfo(promUrl, clusterName);
        Map<String, List<String>> podsPerNode = new HashMap<>();
        Map<String, Integer> runningPodsPerNode = new HashMap<>();
        for (Map<String, String> podInfo : podInfoList) {
            String nodeName = podInfo.get("node");
            String podName = podInfo.get("pod");
            String namespace = podInfo.get("namespace");
            String phase = podInfo.get("phase");
            podsPerNode.computeIfAbsent(nodeName, k -> new ArrayList<>())
                    .add(namespace + "/" + podName);
            // Count running pods
            if ("Running".equalsIgnoreCase(phase)) {
                runningPodsPerNode.put(nodeName, runningPodsPerNode.getOrDefault(nodeName, 0) + 1);
            }
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
            double cpuCap = 0.0;
            double memCap = 0.0;
            if (nodeCapacity.containsKey(nodeName)) {
                Map<String, Double> capacity = nodeCapacity.get(nodeName);
                cpuCap = capacity.getOrDefault("cpu", 0.0);
                memCap = capacity.getOrDefault("memory", 0.0);
                node.setCpuCapacity(cpuCap);
                node.setMemoryCapacity(memCap);
                node.setPodsCapacity(capacity.getOrDefault("pods", 0.0).intValue());
            }

            // ✅ Allocatable
            if (nodeAllocatable.containsKey(nodeName)) {
                Map<String, Double> allocatable = nodeAllocatable.get(nodeName);
                node.setCpuAllocatable(allocatable.getOrDefault("cpu", 0.0));
                node.setMemoryAllocatable(allocatable.getOrDefault("memory", 0.0));
                node.setPodsAllocatable(allocatable.getOrDefault("pods", 0.0).intValue());
            }

            // ✅ Current usage (calculate percentage from millicores/bytes manually)
            double cpuUsedMillicores = nodeCpuMillicores.getOrDefault(nodeName, 0.0);
            node.setCpuUsageMillicores(cpuUsedMillicores);
            if (cpuCap > 0) {
                node.setCpuUsagePercent((cpuUsedMillicores / (cpuCap * 1000)) * 100);
            } else {
                node.setCpuUsagePercent(0.0);
            }

            double memUsedBytes = nodeMemUsageBytes.getOrDefault(nodeName, 0.0);
            // node.setMemoryUsageBytes(memUsedBytes); // Add if field exists in DTO
            if (memCap > 0) {
                double memCapBytes = memCap * 1024 * 1024 * 1024;
                node.setMemoryUsagePercent((memUsedBytes / memCapBytes) * 100);
            } else {
                node.setMemoryUsagePercent(0.0);
            }

            // ✅ Requests
            double cpuReq = nodeCpuRequests.getOrDefault(nodeName, 0.0);
            node.setCpuRequests(cpuReq);
            if (cpuCap > 0) {
                node.setCpuRequestsPercent((cpuReq / (cpuCap * 1000)) * 100);
            } else {
                node.setCpuRequestsPercent(0.0);
            }

            double memReq = nodeMemRequests.getOrDefault(nodeName, 0.0);
            node.setMemoryRequests(memReq / (1024 * 1024)); // bytes to MB
            if (memCap > 0) {
                double memCapBytes = memCap * 1024 * 1024 * 1024;
                node.setMemoryRequestsPercent((memReq / memCapBytes) * 100);
            } else {
                node.setMemoryRequestsPercent(0.0);
            }

            // ✅ Conditions (Ready, DiskPressure, MemoryPressure, PIDPressure)
            List<String> conditions = allNodeConditions.getOrDefault(nodeName, new ArrayList<>());
            node.setConditions(conditions);

            // ✅ NEW: Enhanced node fields
            node.setCapacityType(nodeCapacityTypes.getOrDefault(nodeName, "unknown"));
            node.setNodeGroup(nodeGroups.getOrDefault(nodeName, null));
            node.setPidPressure(nodePIDPressure.getOrDefault(nodeName, false));

            // Extract pressure conditions from allNodeConditions
            node.setDiskPressure(conditions.contains("DiskPressure"));
            node.setMemoryPressure(conditions.contains("MemoryPressure"));

            // ✅ Instance type from node labels
            if (nodeLabelMap.containsKey(nodeName)) {
                Map<String, String> labels = nodeLabelMap.get(nodeName);
                node.setInstanceType(labels.getOrDefault("label_node_kubernetes_io_instance_type", "Unknown"));
            } else {
                node.setInstanceType("Unknown");
            }

            // ✅ System reserved resources (calculated from capacity - allocatable)
            if (nodeCapacity.containsKey(nodeName) && nodeAllocatable.containsKey(nodeName)) {
                Map<String, Double> capacity = nodeCapacity.get(nodeName);
                Map<String, Double> allocatable = nodeAllocatable.get(nodeName);
                node.setSystemReservedCpu(capacity.getOrDefault("cpu", 0.0) - allocatable.getOrDefault("cpu", 0.0));
                node.setSystemReservedMemory(
                        capacity.getOrDefault("memory", 0.0) - allocatable.getOrDefault("memory", 0.0));
            }

            // ✅ Pods on this node
            List<String> pods = podsPerNode.getOrDefault(nodeName, new ArrayList<>());
            node.setPods(pods);
            node.setPodCount(pods.size());
            node.setRunningPodsCount(runningPodsPerNode.getOrDefault(nodeName, 0));

            nodes.add(node);
        }

        // ✅ NEW: Enrich nodes with AWS Metadata (IAM Role, Subnets, Security Groups)
        enrichNodesWithAwsMetadata(account, region, nodes);

        logger.info("✅ Built {} node details with conditions and pod placement", nodes.size());
        return nodes;
    }

    /**
     * Enrich nodes with metadata from AWS EC2 API
     */
    private void enrichNodesWithAwsMetadata(CloudAccount account, String region,
            List<EksCompleteDashboardDto.NodeDetail> nodes) {
        try {
            List<String> instanceIds = nodes.stream()
                    .map(EksCompleteDashboardDto.NodeDetail::getInstanceId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .collect(Collectors.toList());

            if (instanceIds.isEmpty()) {
                logger.warn("No instance IDs found to enrich node metadata");
                return;
            }

            logger.info("Enriching {} nodes with AWS metadata in region {}", instanceIds.size(), region);
            Ec2Client ec2 = awsClientProvider.getEc2Client(account, region);

            // Describe instances in batches if necessary (AWS allows up to 1000 IDs, we
            // likely
            // have fewer)
            DescribeInstancesResponse response = ec2.describeInstances(DescribeInstancesRequest.builder()
                    .instanceIds(instanceIds)
                    .build());

            Map<String, Instance> instanceMap = response.reservations().stream()
                    .flatMap(r -> r.instances().stream())
                    .collect(Collectors.toMap(Instance::instanceId, i -> i, (a, b) -> a));

            for (EksCompleteDashboardDto.NodeDetail node : nodes) {
                if (node.getInstanceId() != null && instanceMap.containsKey(node.getInstanceId())) {
                    Instance inst = instanceMap.get(node.getInstanceId());

                    // Set Subnet
                    node.setSubnetIds(Collections.singletonList(inst.subnetId()));

                    // Set Security Groups
                    List<String> sgIds = inst.securityGroups().stream()
                            .map(software.amazon.awssdk.services.ec2.model.GroupIdentifier::groupId)
                            .collect(Collectors.toList());
                    node.setSecurityGroupIds(sgIds);

                    // Set IAM Role / Profile
                    if (inst.iamInstanceProfile() != null) {
                        String profileArn = inst.iamInstanceProfile().arn();
                        node.setInstanceProfile(profileArn);
                        // Extract role name from ARN if possible (conventionally the last part)
                        if (profileArn != null && profileArn.contains("/")) {
                            node.setIamRole(profileArn.substring(profileArn.lastIndexOf("/") + 1));
                        }
                    }
                }
            }
            logger.info("✅ Successfully enriched nodes with AWS metadata");

        } catch (Exception e) {
            logger.error("❌ Error enriching nodes with AWS metadata: {}", e.getMessage());
        }
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
    private NodeMetrics buildNodeMetrics(CloudAccount account, String region, String promUrl, String clusterName) {
        NodeMetrics metrics = new NodeMetrics();

        try {
            // Get node condition counts
            Map<String, Integer> conditionCounts = prometheusService.getNodeConditionCounts(promUrl, clusterName);
            metrics.setConditionsReady(conditionCounts.getOrDefault("Ready", 0));
            metrics.setConditionsDiskPressure(conditionCounts.getOrDefault("DiskPressure", 0));
            metrics.setConditionsMemoryPressure(conditionCounts.getOrDefault("MemoryPressure", 0));
            metrics.setConditionsPidPressure(conditionCounts.getOrDefault("PIDPressure", 0));

            // Get node details from existing method
            List<NodeDetail> nodeDetails = buildNodeDetails(account, region, promUrl, clusterName);

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
                info.setDiskPressure(detail.getDiskPressure() != null ? detail.getDiskPressure() : false);
                info.setMemoryPressure(detail.getMemoryPressure() != null ? detail.getMemoryPressure() : false);
                info.setPidPressure(detail.getPidPressure() != null ? detail.getPidPressure() : false);
                info.setCpuCapacity(detail.getCpuCapacity());
                info.setCpuAllocatable(detail.getCpuAllocatable());
                info.setCpuUsage(detail.getCpuUsagePercent());
                info.setCpuUsageMillicores(detail.getCpuUsageMillicores());
                info.setMemoryCapacity(detail.getMemoryCapacity());
                info.setMemoryAllocatable(detail.getMemoryAllocatable());
                info.setMemoryUsage(detail.getMemoryUsagePercent());
                info.setPodCapacity(detail.getPodsCapacity());
                info.setPodCount(detail.getPodCount());
                info.setRunningPodsCount(detail.getRunningPodsCount());
                info.setKubeletVersion(detail.getKubeletVersion());
                info.setZone(detail.getZone());
                info.setInstanceId(detail.getInstanceId());
                info.setInstanceType(detail.getInstanceType());
                info.setCapacityType(detail.getCapacityType());
                info.setNodeGroup(detail.getNodeGroup());
                info.setSystemReservedCpu(detail.getSystemReservedCpu());
                info.setSystemReservedMemory(detail.getSystemReservedMemory());

                // ✅ NEW: Request metrics
                info.setCpuRequests(detail.getCpuRequests());
                info.setMemoryRequests(detail.getMemoryRequests());
                info.setCpuRequestsPercent(detail.getCpuRequestsPercent());
                info.setMemoryRequestsPercent(detail.getMemoryRequestsPercent());

                // ✅ NEW: Map AWS Metadata fields
                info.setIamRole(detail.getIamRole());
                info.setInstanceProfile(detail.getInstanceProfile());
                info.setSubnetIds(detail.getSubnetIds());
                info.setSecurityGroupIds(detail.getSecurityGroupIds());

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

    /**
     * Build aggregated pod resource metrics for Pods tab charts
     */
    private com.xammer.cloud.dto.k8s.PodResourceMetrics buildPodResourceMetrics(String promUrl, String clusterName) {
        com.xammer.cloud.dto.k8s.PodResourceMetrics metrics = new com.xammer.cloud.dto.k8s.PodResourceMetrics();

        try {
            logger.info("🔍 Building pod resource metrics aggregates...");

            // Get aggregated data from Prometheus
            Map<String, Double> aggregates = prometheusService.getPodResourceAggregates(promUrl, clusterName);

            // Get cluster capacity for context
            Map<String, Map<String, Double>> nodeCapacity = prometheusService.getNodeCapacity(promUrl, clusterName);
            double totalCpuCapacity = nodeCapacity.values().stream()
                    .mapToDouble(m -> m.getOrDefault("cpu", 0.0))
                    .sum() * 1000; // Convert to millicores
            double totalMemoryCapacity = nodeCapacity.values().stream()
                    .mapToDouble(m -> m.getOrDefault("memory", 0.0))
                    .sum();

            // Populate metrics
            metrics.setTotalCpuRequests(aggregates.getOrDefault("totalCpuRequests", 0.0));
            metrics.setTotalCpuLimits(aggregates.getOrDefault("totalCpuLimits", 0.0));
            metrics.setTotalCpuUsage(aggregates.getOrDefault("totalCpuUsage", 0.0));
            metrics.setTotalCpuCapacity(totalCpuCapacity);

            metrics.setTotalMemoryRequests(aggregates.getOrDefault("totalMemoryRequests", 0.0));
            metrics.setTotalMemoryLimits(aggregates.getOrDefault("totalMemoryLimits", 0.0));
            metrics.setTotalMemoryUsage(aggregates.getOrDefault("totalMemoryUsage", 0.0));
            metrics.setTotalMemoryCapacity(totalMemoryCapacity);

            metrics.setPodsWithoutRequests(aggregates.getOrDefault("podsWithoutRequests", 0.0).intValue());
            metrics.setPodsWithoutLimits(aggregates.getOrDefault("podsWithoutLimits", 0.0).intValue());

            logger.info("✅ Pod resource metrics: CPU req={}, lim={}, usage={}; Memory req={}, lim={}, usage={}",
                    metrics.getTotalCpuRequests(),
                    metrics.getTotalCpuLimits(),
                    metrics.getTotalCpuUsage(),
                    metrics.getTotalMemoryRequests(),
                    metrics.getTotalMemoryLimits(),
                    metrics.getTotalMemoryUsage());

        } catch (Exception e) {
            logger.error("❌ Error building pod resource metrics", e);
        }

        return metrics;
    }
}