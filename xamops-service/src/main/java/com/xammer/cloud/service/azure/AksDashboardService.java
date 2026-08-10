package com.xammer.cloud.service.azure;

import com.azure.resourcemanager.AzureResourceManager;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.KubernetesCluster;
import com.xammer.cloud.domain.KubernetesClusterConfig;
import com.xammer.cloud.dto.azure.AksDashboardDto;
import com.xammer.cloud.dto.gcp.GkeDashboardDto;
import com.xammer.cloud.dto.k8s.KubescapeDashboard;
import com.xammer.cloud.dto.k8s.TrivyDashboard;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import com.xammer.cloud.repository.KubernetesClusterRepository;
import com.xammer.cloud.service.AgentDataTransformerService;
import com.xammer.cloud.service.AgentK8sDashboardMapper;
import com.xammer.cloud.service.PrometheusService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AksDashboardService {

    private final AzureClientProvider azureClientProvider;
    private final CloudAccountRepository cloudAccountRepository;
    private final KubernetesClusterRepository kubernetesClusterRepository;
    private final PrometheusService prometheusService;
    private final KubernetesClusterConfigRepository kubernetesClusterConfigRepository;
    private final AgentDataTransformerService agentDataTransformerService;
    private final AgentK8sDashboardMapper agentK8sDashboardMapper;

    public AksDashboardDto getDashboard(String subscriptionId, String clusterName, String resourceGroup) {
        log.info("Building AKS dashboard for cluster {} in resource group {} (subscription {})",
                clusterName, resourceGroup, subscriptionId);

        AksDashboardDto dto = new AksDashboardDto();
        dto.setClusterName(clusterName);
        dto.setResourceGroup(resourceGroup);
        dto.setLastSyncedAt(LocalDateTime.now());

        // Load account
        CloudAccount account = cloudAccountRepository.findByAzureSubscriptionId(subscriptionId)
                .orElseThrow(() -> new RuntimeException("Azure account not found: " + subscriptionId));

        // Get cluster info from Azure SDK
        try {
            AzureResourceManager azure = azureClientProvider.getAzureClient(subscriptionId);
            com.azure.resourcemanager.containerservice.models.KubernetesCluster aks =
                    azure.kubernetesClusters().getByResourceGroup(resourceGroup, clusterName);

            dto.setLocation(aks.regionName());
            dto.setVersion(aks.version());
            String provState = "Unknown";
            try {
                if (aks.innerModel().provisioningState() != null) {
                    provState = aks.innerModel().provisioningState().toString();
                }
            } catch (Exception ignored) {}
            dto.setStatus(provState);

            // Map agent pools → GkeNodePoolDto (reuse same DTO for JSON shape compatibility)
            List<GkeDashboardDto.GkeNodePoolDto> pools = aks.agentPools().values().stream()
                    .map(pool -> {
                        GkeDashboardDto.GkeNodePoolDto p = new GkeDashboardDto.GkeNodePoolDto();
                        p.setName(pool.name());
                        p.setMachineType(pool.vmSize() != null ? pool.vmSize().toString() : "Unknown");
                        p.setNodeCount(pool.count());
                        p.setVersion(pool.innerModel().orchestratorVersion() != null ? pool.innerModel().orchestratorVersion() : "");
                        p.setAutoscalingEnabled(Boolean.TRUE.equals(pool.innerModel().enableAutoScaling()));
                        p.setMinNodes(pool.innerModel().minCount() != null ? pool.innerModel().minCount() : 0);
                        p.setMaxNodes(pool.innerModel().maxCount() != null ? pool.innerModel().maxCount() : 0);
                        String poolState = pool.provisioningState() != null ? pool.provisioningState() : "Unknown";
                        p.setStatus(poolState);
                        return p;
                    })
                    .collect(Collectors.toList());
            dto.setNodePools(pools);

        } catch (Exception e) {
            log.error("Error fetching AKS cluster details for {}/{}: {}", subscriptionId, clusterName, e.getMessage());
            dto.setNodePools(Collections.emptyList());
        }

        // ── Data source 1: XamOps agent (the only path AKS onboarding actually creates)
        // AKS clusters are agent-onboarded and auto-register on first push, so their
        // data lives in agent_data_cache. Nothing sets kubernetes_cluster.prometheus_url
        // for them, which is why this used to report "not connected" while the agent was
        // pushing valid data every interval.
        boolean populatedFromAgent = populateFromAgentData(dto, account.getId(), clusterName);

        // ── Data source 2: direct Prometheus (kubeconfig-style onboarding)
        // Requires prometheus_url to be set AND the XamOps server to reach the cluster's
        // Prometheus inbound. Still supported, and still authoritative when available.
        Optional<KubernetesCluster> saved =
                kubernetesClusterRepository.findByCloudAccount_IdAndClusterName(account.getId(), clusterName);
        String promUrl = saved.map(KubernetesCluster::getPrometheusUrl).orElse(null);
        boolean hasPrometheus = promUrl != null && !promUrl.isBlank();

        if (hasPrometheus) {
            try { dto.setOverview(buildOverview(promUrl, clusterName)); } catch (Exception ignored) {}
            try { dto.setWorkloadHealth(buildWorkloadHealth(promUrl, clusterName)); } catch (Exception ignored) {}
            try { dto.setResourceUtilization(buildResourceUtilization(promUrl, clusterName)); } catch (Exception ignored) {}
            try { dto.setNodeMetrics(buildNodeMetrics(promUrl, clusterName)); } catch (Exception ignored) {}
            try { dto.setContainerMetrics(buildContainerMetrics(promUrl, clusterName)); } catch (Exception ignored) {}
            try { dto.setCostMetrics(buildCostMetrics(promUrl, clusterName)); } catch (Exception ignored) {}
            try { dto.setSecurityMetrics(buildSecurityMetrics(promUrl, clusterName)); } catch (Exception ignored) {}
        }

        dto.setConnected(hasPrometheus || populatedFromAgent);
        return dto;
    }

    /**
     * Populates the dashboard sections from the agent's cached batches.
     *
     * <p>Each section is mapped independently and failures are swallowed per-section, so a
     * malformed batch degrades that tab rather than blanking the whole dashboard — the same
     * contract the Prometheus path above uses.
     *
     * @return true if at least one section was populated, meaning the cluster genuinely
     *         has monitoring data and should not be shown as disconnected
     */
    private boolean populateFromAgentData(AksDashboardDto dto, Long cloudAccountId, String clusterName) {
        Optional<KubernetesClusterConfig> configOpt =
                kubernetesClusterConfigRepository.findByCloudAccountIdAndClusterName(cloudAccountId, clusterName);
        if (configOpt.isEmpty()) {
            log.debug("No cluster config for AKS cluster {} — skipping agent data", clusterName);
            return false;
        }

        Long configId = configOpt.get().getId();
        if (!agentDataTransformerService.hasAgentData(configId)) {
            log.debug("No agent data cached for AKS cluster {} (configId={})", clusterName, configId);
            return false;
        }

        AgentDataTransformerService.K8sResourcesResult k8s;
        try {
            k8s = agentDataTransformerService.transformK8sResources(configId);
        } catch (Exception e) {
            log.warn("Failed to read agent k8s-resources for AKS cluster {}: {}", clusterName, e.getMessage());
            return false;
        }

        // k8s-resources is the batch every other section derives from. Without nodes or
        // pods there is nothing to show, so report not-connected rather than empty tabs.
        if (k8s.getTotalNodes() == 0 && k8s.getTotalPods() == 0) {
            log.debug("Agent data present but empty for AKS cluster {}", clusterName);
            return false;
        }

        AgentDataTransformerService.PrometheusMetricsResult prom = null;
        try {
            prom = agentDataTransformerService.transformPrometheusMetrics(configId);
        } catch (Exception e) {
            log.debug("No agent prometheus-metrics for AKS cluster {}: {}", clusterName, e.getMessage());
        }

        final AgentDataTransformerService.PrometheusMetricsResult promMetrics = prom;
        try { dto.setOverview(agentK8sDashboardMapper.buildOverview(k8s, promMetrics)); } catch (Exception ignored) {}
        try { dto.setWorkloadHealth(agentK8sDashboardMapper.buildWorkloadHealth(k8s)); } catch (Exception ignored) {}
        try { dto.setResourceUtilization(agentK8sDashboardMapper.buildResourceUtilization(k8s)); } catch (Exception ignored) {}
        try { dto.setNodeMetrics(agentK8sDashboardMapper.buildNodeMetrics(k8s)); } catch (Exception ignored) {}
        try { dto.setContainerMetrics(agentK8sDashboardMapper.buildContainerMetrics(k8s)); } catch (Exception ignored) {}

        // Security: Trivy and Kubescape come from their own batches, Falco events ride
        // along in prometheus-metrics. Each is optional — a cluster running only Trivy
        // still gets a populated section with the other two flagged as not running.
        TrivyDashboard trivy = null;
        try {
            trivy = agentDataTransformerService.transformTrivyData(configId);
        } catch (Exception e) {
            log.debug("No agent Trivy data for AKS cluster {}: {}", clusterName, e.getMessage());
        }
        KubescapeDashboard kubescape = null;
        try {
            kubescape = agentDataTransformerService.transformKubescapeData(configId);
        } catch (Exception e) {
            log.debug("No agent Kubescape data for AKS cluster {}: {}", clusterName, e.getMessage());
        }
        try {
            dto.setSecurityMetrics(
                    agentK8sDashboardMapper.buildSecurityMetrics(trivy, kubescape, promMetrics));
        } catch (Exception ignored) {}

        // Agent reports the live server version; prefer it over the Azure SDK's channel
        // value (e.g. "1.35" from the SDK vs "1.35.6" actually running).
        if (k8s.getK8sVersion() != null && !k8s.getK8sVersion().isBlank()) {
            dto.setVersion(k8s.getK8sVersion());
        }

        log.info("AKS dashboard for {} populated from agent data: {} nodes, {} pods, {} namespaces",
                clusterName, k8s.getTotalNodes(), k8s.getTotalPods(), k8s.getTotalNamespaces());
        return true;
    }

    // ── Prometheus builder methods (cloud-agnostic — copied from GkeDashboardService) ──

    private GkeDashboardDto.GkeOverviewDto buildOverview(String promUrl, String clusterName) {
        GkeDashboardDto.GkeOverviewDto overview = new GkeDashboardDto.GkeOverviewDto();
        try { overview.setTotalNodes(safeInt(prometheusService.getClusterNodeCount(promUrl, clusterName))); } catch (Exception ignored) {}
        try { overview.setTotalPods(safeInt(prometheusService.getClusterPodCount(promUrl, clusterName))); } catch (Exception ignored) {}
        try { overview.setTotalNamespaces(prometheusService.getNamespaceStatusPhase(promUrl, clusterName).size()); } catch (Exception ignored) {}
        try { overview.setCpuUsageMillicores(safeDouble(prometheusService.getClusterCpuUsageMillicores(promUrl, clusterName))); } catch (Exception ignored) {}
        try { overview.setCpuCapacityMillicores(safeDouble(prometheusService.getClusterCpuCapacityMillicores(promUrl, clusterName))); } catch (Exception ignored) {}
        try { overview.setCpuUsagePercent(safeDouble(prometheusService.getClusterCpuUsage(promUrl, clusterName))); } catch (Exception ignored) {}
        try { overview.setMemoryUsageBytes(safeDouble(prometheusService.getClusterMemoryUsageBytes(promUrl, clusterName))); } catch (Exception ignored) {}
        try { overview.setMemoryCapacityBytes(safeDouble(prometheusService.getClusterMemoryCapacityBytes(promUrl, clusterName))); } catch (Exception ignored) {}
        try { overview.setMemoryUsagePercent(safeDouble(prometheusService.getClusterMemoryUsage(promUrl, clusterName))); } catch (Exception ignored) {}
        return overview;
    }

    private GkeDashboardDto.GkeWorkloadHealthDto buildWorkloadHealth(String promUrl, String clusterName) {
        GkeDashboardDto.GkeWorkloadHealthDto health = new GkeDashboardDto.GkeWorkloadHealthDto();
        health.setPods(buildPods(promUrl, clusterName));
        health.setDeployments(buildDeployments(promUrl, clusterName));
        health.setStatefulsets(buildStatefulsets(promUrl, clusterName));
        health.setDaemonsets(buildDaemonsets(promUrl, clusterName));
        List<GkeDashboardDto.GkePodDto> pods = health.getPods();
        if (!pods.isEmpty()) {
            long running = pods.stream().filter(p -> "Running".equalsIgnoreCase(p.getPhase())).count();
            health.setClusterHealthScore((double) running / pods.size() * 100);
        }
        return health;
    }

    private List<GkeDashboardDto.GkePodDto> buildPods(String promUrl, String clusterName) {
        try {
            List<Map<String, String>> podInfoList = prometheusService.getPodInfo(promUrl, clusterName);
            Map<String, String> podPhases = prometheusService.getPodStatusPhase(promUrl, clusterName);
            Map<String, Double> cpuUsage = safeMap(prometheusService.getPodCpuUsage(promUrl, clusterName));
            Map<String, Double> memUsage = safeMap(prometheusService.getPodMemoryUsage(promUrl, clusterName));
            Map<String, Double> restarts = safeMap(prometheusService.getPodRestartCount(promUrl, clusterName));
            Map<String, Double> cpuReq = safeMap(prometheusService.getPodCpuRequests(promUrl, clusterName));
            Map<String, Double> memReq = safeMap(prometheusService.getPodMemoryRequests(promUrl, clusterName));
            Map<String, Double> creationTimes = safeMap(prometheusService.getPodCreationTime(promUrl, clusterName));

            List<GkeDashboardDto.GkePodDto> result = new ArrayList<>();
            for (Map<String, String> info : podInfoList) {
                String podName = info.getOrDefault("pod", info.getOrDefault("name", ""));
                String namespace = info.getOrDefault("namespace", "");
                String phase = podPhases.getOrDefault(podName, info.getOrDefault("phase", "Unknown"));
                String node = info.getOrDefault("node", info.getOrDefault("node_name", ""));

                GkeDashboardDto.GkePodDto pod = new GkeDashboardDto.GkePodDto();
                pod.setName(podName);
                pod.setNamespace(namespace);
                pod.setPhase(phase);
                pod.setNodeName(node);
                pod.setCpuUsage(cpuUsage.getOrDefault(podName, 0.0));
                pod.setMemoryUsage(memUsage.getOrDefault(podName, 0.0));
                pod.setRestartCount(restarts.getOrDefault(podName, 0.0).intValue());
                pod.setCpuRequest(cpuReq.getOrDefault(podName, 0.0));
                pod.setMemoryRequest(memReq.getOrDefault(podName, 0.0));
                Double createdAt = creationTimes.get(podName);
                if (createdAt != null && createdAt > 0) {
                    pod.setAge(computeAge(createdAt.longValue()));
                }
                result.add(pod);
            }
            return result;
        } catch (Exception e) {
            log.warn("Error building pods for {}: {}", clusterName, e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<GkeDashboardDto.GkeDeploymentDto> buildDeployments(String promUrl, String clusterName) {
        try {
            List<Map<String, String>> deployList = prometheusService.getClusterDeployments(promUrl, clusterName);
            Map<String, Double> available = safeMap(prometheusService.getDeploymentAvailableReplicas(promUrl, clusterName));
            Map<String, Double> desired = safeMap(prometheusService.getDeploymentSpecReplicas(promUrl, clusterName));
            List<GkeDashboardDto.GkeDeploymentDto> result = new ArrayList<>();
            for (Map<String, String> d : deployList) {
                String name = d.getOrDefault("deployment", d.getOrDefault("name", ""));
                String namespace = d.getOrDefault("namespace", "");
                int desiredCount = desired.getOrDefault(name, 0.0).intValue();
                int availableCount = available.getOrDefault(name, 0.0).intValue();
                GkeDashboardDto.GkeDeploymentDto dep = new GkeDashboardDto.GkeDeploymentDto();
                dep.setName(name);
                dep.setNamespace(namespace);
                dep.setReplicas(desiredCount);
                dep.setAvailableReplicas(availableCount);
                dep.setReadyReplicas(availableCount);
                result.add(dep);
            }
            return result;
        } catch (Exception e) {
            log.warn("Error building deployments for {}: {}", clusterName, e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<GkeDashboardDto.GkeWorkloadDto> buildStatefulsets(String promUrl, String clusterName) {
        try {
            return prometheusService.getClusterStatefulsets(promUrl, clusterName).stream().map(ss -> {
                GkeDashboardDto.GkeWorkloadDto w = new GkeDashboardDto.GkeWorkloadDto();
                w.setName(ss.getOrDefault("statefulset", ss.getOrDefault("name", "")));
                w.setNamespace(ss.getOrDefault("namespace", ""));
                w.setKind("StatefulSet");
                w.setReplicas(parseInt(ss.get("replicas_desired")));
                w.setReadyReplicas(parseInt(ss.get("replicas_ready")));
                return w;
            }).collect(Collectors.toList());
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private List<GkeDashboardDto.GkeWorkloadDto> buildDaemonsets(String promUrl, String clusterName) {
        try {
            return prometheusService.getClusterDaemonsets(promUrl, clusterName).stream().map(ds -> {
                GkeDashboardDto.GkeWorkloadDto w = new GkeDashboardDto.GkeWorkloadDto();
                w.setName(ds.getOrDefault("daemonset", ds.getOrDefault("name", "")));
                w.setNamespace(ds.getOrDefault("namespace", ""));
                w.setKind("DaemonSet");
                w.setReplicas(parseInt(ds.get("desired_number_scheduled")));
                w.setReadyReplicas(parseInt(ds.get("number_ready")));
                return w;
            }).collect(Collectors.toList());
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private GkeDashboardDto.GkeResourceUtilizationDto buildResourceUtilization(String promUrl, String clusterName) {
        GkeDashboardDto.GkeResourceUtilizationDto util = new GkeDashboardDto.GkeResourceUtilizationDto();
        try {
            List<Map<String, String>> nodeInfoList = prometheusService.getNodeInfo(promUrl, clusterName);
            Map<String, Double> cpuUsage = safeMap(prometheusService.getNodeCpuUsage(promUrl, clusterName));
            Map<String, Double> memUsage = safeMap(prometheusService.getNodeMemoryUsage(promUrl, clusterName));
            Map<String, Double> cpuReq = safeMap(prometheusService.getNodeCpuRequests(promUrl, clusterName));
            Map<String, Double> memReq = safeMap(prometheusService.getNodeMemoryRequests(promUrl, clusterName));
            Map<String, Map<String, Double>> capacity = prometheusService.getNodeCapacity(promUrl, clusterName);
            if (capacity == null) capacity = Collections.emptyMap();

            List<GkeDashboardDto.GkeNodeResourceDto> nodes = new ArrayList<>();
            for (Map<String, String> info : nodeInfoList) {
                String nodeName = info.getOrDefault("node", info.getOrDefault("name", ""));
                Map<String, Double> cap = capacity.getOrDefault(nodeName, Collections.emptyMap());
                double cpuCap = cap.getOrDefault("cpu", 1.0) * 1000;
                double memCapBytes = cap.getOrDefault("memory", 1.0);

                GkeDashboardDto.GkeNodeResourceDto n = new GkeDashboardDto.GkeNodeResourceDto();
                n.setNodeName(nodeName);
                double cpuPct = cpuUsage.getOrDefault(nodeName, 0.0);
                double memPct = memUsage.getOrDefault(nodeName, 0.0);
                n.setCpuUsagePercent(cpuPct);
                n.setMemoryUsagePercent(memPct);
                n.setCpuUsageMillicores(cpuCap > 0 ? cpuPct * cpuCap / 100 : 0);
                n.setCpuCapacityMillicores(cpuCap);
                n.setCpuRequestsPercent(cpuCap > 0 ? cpuReq.getOrDefault(nodeName, 0.0) / cpuCap * 100 : 0);
                n.setMemoryRequestsPercent(memCapBytes > 1.0 ? Math.min(200, memReq.getOrDefault(nodeName, 0.0) / memCapBytes * 100) : 0);
                nodes.add(n);
            }
            util.setNodes(nodes);
        } catch (Exception e) {
            log.warn("Error building resource utilization for {}: {}", clusterName, e.getMessage());
            util.setNodes(Collections.emptyList());
        }
        return util;
    }

    private GkeDashboardDto.GkeNodeMetricsDto buildNodeMetrics(String promUrl, String clusterName) {
        GkeDashboardDto.GkeNodeMetricsDto nodeMetrics = new GkeDashboardDto.GkeNodeMetricsDto();
        try {
            List<Map<String, String>> nodeInfoList = prometheusService.getNodeInfo(promUrl, clusterName);
            Map<String, String> conditions = safeMap(prometheusService.getNodeConditions(promUrl, clusterName));
            Map<String, Map<String, Double>> capacity = prometheusService.getNodeCapacity(promUrl, clusterName);
            if (capacity == null) capacity = Collections.emptyMap();
            Map<String, Double> cpuMilli = safeMap(prometheusService.getNodeCpuUsageMillicores(promUrl, clusterName));
            Map<String, Double> memBytes = safeMap(prometheusService.getNodeMemoryUsageBytes(promUrl, clusterName));
            Map<String, Double> cpuUsagePct = safeMap(prometheusService.getNodeCpuUsage(promUrl, clusterName));
            Map<String, Double> memUsagePct = safeMap(prometheusService.getNodeMemoryUsage(promUrl, clusterName));
            List<Map<String, String>> nodeLabels = prometheusService.getNodeLabels(promUrl, clusterName);
            Map<String, Map<String, String>> labelMap = new HashMap<>();
            if (nodeLabels != null) {
                for (Map<String, String> lbl : nodeLabels) {
                    labelMap.put(lbl.getOrDefault("node", ""), lbl);
                }
            }

            List<GkeDashboardDto.GkeNodeInfoDto> nodes = new ArrayList<>();
            for (Map<String, String> info : nodeInfoList) {
                String nodeName = info.getOrDefault("node", info.getOrDefault("name", ""));
                String condition = conditions.getOrDefault(nodeName, "Ready");
                Map<String, Double> cap = capacity.getOrDefault(nodeName, Collections.emptyMap());
                Map<String, String> lbl = labelMap.getOrDefault(nodeName, Collections.emptyMap());

                GkeDashboardDto.GkeNodeInfoDto n = new GkeDashboardDto.GkeNodeInfoDto();
                n.setName(nodeName);
                n.setReady("Ready".equalsIgnoreCase(condition));
                n.setDiskPressure("DiskPressure".equalsIgnoreCase(condition));
                n.setMemoryPressure("MemoryPressure".equalsIgnoreCase(condition));
                n.setInstanceType(lbl.getOrDefault("label_node_kubernetes_io_instance_type",
                        lbl.getOrDefault("label_beta_kubernetes_io_instance_type", "")));
                n.setZone(lbl.getOrDefault("label_topology_kubernetes_io_zone", ""));
                n.setNodePool(lbl.getOrDefault("label_agentpool", lbl.getOrDefault("label_kubernetes_azure_com_agentpool", "")));
                n.setKubeletVersion(info.getOrDefault("kubelet_version", ""));
                n.setInternalIp(info.getOrDefault("internal_ip", ""));
                n.setCpuCapacity(cap.getOrDefault("cpu", 0.0));
                n.setMemoryCapacity(cap.getOrDefault("memory", 0.0));
                n.setCpuUsage(cpuUsagePct.getOrDefault(nodeName, 0.0));
                n.setCpuUsageMillicores(cpuMilli.getOrDefault(nodeName, 0.0));
                n.setMemoryUsage(memUsagePct.getOrDefault(nodeName, 0.0));
                nodes.add(n);
            }
            nodeMetrics.setNodes(nodes);
        } catch (Exception e) {
            log.warn("Error building node metrics for {}: {}", clusterName, e.getMessage());
            nodeMetrics.setNodes(Collections.emptyList());
        }
        return nodeMetrics;
    }

    private GkeDashboardDto.GkeContainerMetricsDto buildContainerMetrics(String promUrl, String clusterName) {
        GkeDashboardDto.GkeContainerMetricsDto containerMetrics = new GkeDashboardDto.GkeContainerMetricsDto();
        try {
            List<Map<String, String>> podInfoList = prometheusService.getPodInfo(promUrl, clusterName);
            Map<String, Double> cpuUsage = safeMap(prometheusService.getPodCpuUsage(promUrl, clusterName));
            Map<String, Double> memUsage = safeMap(prometheusService.getPodMemoryUsage(promUrl, clusterName));
            Map<String, Double> restarts = safeMap(prometheusService.getPodRestartCount(promUrl, clusterName));

            List<GkeDashboardDto.GkeContainerDto> containers = new ArrayList<>();
            for (Map<String, String> info : podInfoList) {
                String podName = info.getOrDefault("pod", info.getOrDefault("name", ""));
                String namespace = info.getOrDefault("namespace", "");
                String phase = info.getOrDefault("phase", "Unknown");

                GkeDashboardDto.GkeContainerDto c = new GkeDashboardDto.GkeContainerDto();
                c.setName(podName);
                c.setPod(podName);
                c.setNamespace(namespace);
                c.setImage(info.getOrDefault("image", ""));
                c.setState("Running".equalsIgnoreCase(phase) ? "running" : "waiting");
                c.setReady("Running".equalsIgnoreCase(phase));
                c.setRestartCount(restarts.getOrDefault(podName, 0.0).intValue());
                c.setCpuUsage(cpuUsage.getOrDefault(podName, 0.0));
                c.setMemoryUsage(memUsage.getOrDefault(podName, 0.0));
                containers.add(c);
            }
            containerMetrics.setContainers(containers);
        } catch (Exception e) {
            log.warn("Error building container metrics for {}: {}", clusterName, e.getMessage());
            containerMetrics.setContainers(Collections.emptyList());
        }
        return containerMetrics;
    }

    private GkeDashboardDto.GkeCostMetricsDto buildCostMetrics(String promUrl, String clusterName) {
        GkeDashboardDto.GkeCostMetricsDto cost = new GkeDashboardDto.GkeCostMetricsDto();
        try {
            Double rawDailyCost = prometheusService.getClusterDailyCost(promUrl, clusterName);
            cost.setDailyCost(rawDailyCost != null ? rawDailyCost : 0.0);

            Map<String, Double> nsCpu = safeMap(prometheusService.getNamespaceCpuUsage(promUrl, clusterName));
            Map<String, Double> nsMem = safeMap(prometheusService.getNamespaceMemoryUsage(promUrl, clusterName));
            Map<String, Double> nsPods = safeMap(prometheusService.getNamespacePodCount(promUrl, clusterName));
            List<GkeDashboardDto.GkeNamespaceCostDto> nsCosts = new ArrayList<>();
            Set<String> namespaces = new LinkedHashSet<>(nsCpu.keySet());
            namespaces.addAll(nsMem.keySet());
            for (String ns : namespaces) {
                double cpuMilli = nsCpu.getOrDefault(ns, 0.0);
                double memMb = nsMem.getOrDefault(ns, 0.0);
                double estDaily = estimateDailyCost(cpuMilli, memMb);
                nsCosts.add(new GkeDashboardDto.GkeNamespaceCostDto(ns, cpuMilli, memMb,
                        nsPods.getOrDefault(ns, 0.0).intValue(), estDaily, estDaily * 30));
            }
            nsCosts.sort((a, b) -> Double.compare(b.getDailyCost(), a.getDailyCost()));
            cost.setNamespaceCosts(nsCosts);

            if (cost.getDailyCost() == 0.0 && !nsCosts.isEmpty()) {
                cost.setDailyCost(nsCosts.stream().mapToDouble(GkeDashboardDto.GkeNamespaceCostDto::getDailyCost).sum());
            }

            List<GkeDashboardDto.GkeWorkloadCostDto> workloadCosts = new ArrayList<>();
            try {
                Map<String, Map<String, Double>> deplCpu = prometheusService.getDeploymentCpuUsage(promUrl, clusterName);
                Map<String, Map<String, Double>> deplMem = prometheusService.getDeploymentMemoryUsage(promUrl, clusterName);
                if (deplCpu != null) {
                    for (Map.Entry<String, Map<String, Double>> e : deplCpu.entrySet()) {
                        String deplKey = e.getKey();
                        String[] parts = deplKey.split("/", 2);
                        String ns = parts.length > 1 ? parts[0] : "";
                        String name = parts.length > 1 ? parts[1] : deplKey;
                        double cpuMilli = e.getValue().values().stream().mapToDouble(Double::doubleValue).sum();
                        double memMb = 0;
                        if (deplMem != null && deplMem.containsKey(deplKey)) {
                            memMb = deplMem.get(deplKey).values().stream().mapToDouble(Double::doubleValue).sum();
                        }
                        double estDaily = estimateDailyCost(cpuMilli, memMb);
                        workloadCosts.add(new GkeDashboardDto.GkeWorkloadCostDto(name, ns, "Deployment", cpuMilli, memMb, 0, estDaily, estDaily * 30));
                    }
                }
            } catch (Exception ignored) {}
            workloadCosts.sort((a, b) -> Double.compare(b.getDailyCost(), a.getDailyCost()));
            cost.setWorkloadCosts(workloadCosts);
        } catch (Exception e) {
            log.warn("Error building cost metrics for {}: {}", clusterName, e.getMessage());
            cost.setNamespaceCosts(Collections.emptyList());
            cost.setWorkloadCosts(Collections.emptyList());
        }
        return cost;
    }

    private GkeDashboardDto.GkeSecurityMetricsDto buildSecurityMetrics(String promUrl, String clusterName) {
        GkeDashboardDto.GkeSecurityMetricsDto sec = new GkeDashboardDto.GkeSecurityMetricsDto();
        sec.setEventsByPriority(Collections.emptyMap());
        try {
            Double total = prometheusService.getFalcoEventsTotal(promUrl, clusterName);
            sec.setTotalEvents(total != null ? total : 0.0);
            List<Map<String, String>> byPriority = prometheusService.getFalcoEventsByPriority(promUrl, clusterName);
            if (byPriority != null) {
                Map<String, Integer> priorityMap = new LinkedHashMap<>();
                for (Map<String, String> entry : byPriority) {
                    String priority = entry.getOrDefault("priority", "unknown");
                    int cnt = parseInt(entry.getOrDefault("value", entry.getOrDefault("count", "0")));
                    priorityMap.merge(priority, cnt, Integer::sum);
                }
                sec.setEventsByPriority(priorityMap);
            }
        } catch (Exception ignored) {}

        List<GkeDashboardDto.GkeSecurityAlertDto> alerts = new ArrayList<>();
        try {
            List<Map<String, String>> rawAlerts = prometheusService.getFalcoAlerts(promUrl, clusterName);
            for (Map<String, String> a : rawAlerts) {
                GkeDashboardDto.GkeSecurityAlertDto alert = new GkeDashboardDto.GkeSecurityAlertDto();
                alert.setRule(a.getOrDefault("rule", "Unknown"));
                alert.setPriority(a.getOrDefault("priority", "unknown"));
                alert.setCount(parseInt(a.getOrDefault("value", a.getOrDefault("count", "1"))));
                alert.setNamespace(a.getOrDefault("k8s_ns_name", a.getOrDefault("namespace", "")));
                alert.setLastSeen("recent");
                alerts.add(alert);
            }
        } catch (Exception ignored) {}
        sec.setAlerts(alerts);

        try { sec.setFalcoRunning(prometheusService.isFalcoRunning(promUrl)); } catch (Exception ignored) {}

        try {
            sec.setKubescapeRunning(prometheusService.isKubescapeRunning(promUrl));
            if (sec.isKubescapeRunning()) {
                Map<String, Double> pf = prometheusService.getKubescapePassFail(promUrl);
                sec.setKubescapeTotalFailed(pf.getOrDefault("failed", 0.0).intValue());
                sec.setKubescapeTotalPassed(pf.getOrDefault("passed", 0.0).intValue());
                List<Map<String, String>> controls = prometheusService.getKubescapeControlSummary(promUrl);
                Map<String, Integer> bySeverity = new LinkedHashMap<>();
                List<GkeDashboardDto.GkeKubescapeFindingDto> findings = new ArrayList<>();
                for (Map<String, String> c : controls) {
                    String sev = c.getOrDefault("severity", "Unknown");
                    int cnt = parseInt(c.getOrDefault("value", "1"));
                    bySeverity.merge(sev, cnt, Integer::sum);
                    GkeDashboardDto.GkeKubescapeFindingDto f = new GkeDashboardDto.GkeKubescapeFindingDto();
                    f.setControlName(c.getOrDefault("control_name", "Unknown"));
                    f.setSeverity(sev);
                    f.setFailedResources(cnt);
                    findings.add(f);
                }
                sec.setKubescapeFailsBySeverity(bySeverity);
                sec.setKubescapeFindings(findings.stream().limit(20).collect(Collectors.toList()));
            }
        } catch (Exception ignored) {}

        try {
            sec.setTrivyRunning(prometheusService.isTrivyRunning(promUrl));
            if (sec.isTrivyRunning()) {
                Double total = prometheusService.getTrivyTotalVulnerabilities(promUrl);
                sec.setTrivyTotalVulnerabilities(total != null ? total : 0.0);
                List<Map<String, String>> rows = prometheusService.getTrivyVulnerabilitiesBySeverity(promUrl);
                Map<String, Integer> bySeverity = new LinkedHashMap<>();
                List<GkeDashboardDto.GkeTrivyFindingDto> findings = new ArrayList<>();
                for (Map<String, String> r : rows) {
                    String sev = r.getOrDefault("severity", "UNKNOWN");
                    int cnt = parseInt(r.getOrDefault("value", "1"));
                    bySeverity.merge(sev, cnt, Integer::sum);
                    GkeDashboardDto.GkeTrivyFindingDto f = new GkeDashboardDto.GkeTrivyFindingDto();
                    f.setImage(r.getOrDefault("image_repository", "unknown") + ":" + r.getOrDefault("image_tag", "latest"));
                    f.setNamespace(r.getOrDefault("namespace", ""));
                    f.setSeverity(sev);
                    f.setCount(cnt);
                    findings.add(f);
                }
                sec.setTrivyBySeverity(bySeverity);
                sec.setTrivyFindings(findings.stream().limit(20).collect(Collectors.toList()));
            }
        } catch (Exception ignored) {}

        return sec;
    }

    private double estimateDailyCost(double cpuMillicores, double memoryMb) {
        return (cpuMillicores / 1000.0) * 0.048 * 24 + (memoryMb / 1024.0) * 0.006 * 24;
    }

    private String computeAge(long epochSeconds) {
        long diff = System.currentTimeMillis() / 1000 - epochSeconds;
        if (diff < 3600) return (diff / 60) + "m";
        if (diff < 86400) return (diff / 3600) + "h";
        return (diff / 86400) + "d";
    }

    private int safeInt(Integer v) { return v == null ? 0 : v; }
    private double safeDouble(Double v) { return v == null ? 0.0 : v; }
    private int parseInt(String s) { try { return s == null ? 0 : (int) Double.parseDouble(s); } catch (Exception e) { return 0; } }
    private <K, V> Map<K, V> safeMap(Map<K, V> m) { return m == null ? Collections.emptyMap() : m; }
}
