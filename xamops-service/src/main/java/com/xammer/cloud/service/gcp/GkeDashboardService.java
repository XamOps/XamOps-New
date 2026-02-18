package com.xammer.cloud.service.gcp;

import com.google.cloud.container.v1.ClusterManagerClient;
import com.google.container.v1.Cluster;
import com.google.container.v1.NodePool;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.KubernetesCluster;
import com.xammer.cloud.dto.gcp.GkeDashboardDto;
import com.xammer.cloud.dto.gcp.GkeDashboardDto.*;

import java.util.HashMap;
import java.util.LinkedHashMap;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.KubernetesClusterRepository;
import com.xammer.cloud.service.PrometheusService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class GkeDashboardService {

    private static final Logger log = LoggerFactory.getLogger(GkeDashboardService.class);

    private final GcpClientProvider gcpClientProvider;
    private final CloudAccountRepository cloudAccountRepository;
    private final KubernetesClusterRepository kubernetesClusterRepository;
    private final PrometheusService prometheusService;

    public GkeDashboardService(GcpClientProvider gcpClientProvider,
                                CloudAccountRepository cloudAccountRepository,
                                KubernetesClusterRepository kubernetesClusterRepository,
                                PrometheusService prometheusService) {
        this.gcpClientProvider = gcpClientProvider;
        this.cloudAccountRepository = cloudAccountRepository;
        this.kubernetesClusterRepository = kubernetesClusterRepository;
        this.prometheusService = prometheusService;
    }

    /**
     * Builds the complete GKE cluster dashboard.
     * Node pools are always populated from the GCP API.
     * Workload/pod metrics are populated only when a Prometheus URL is available (connected cluster).
     */
    public GkeDashboardDto getDashboard(String gcpProjectId, String clusterName, String location) {
        log.info("Building GKE dashboard for cluster {} in project {} ({})", clusterName, gcpProjectId, location);

        GkeDashboardDto dto = new GkeDashboardDto();
        dto.setClusterName(clusterName);
        dto.setLocation(location);
        dto.setLastSyncedAt(LocalDateTime.now());

        Optional<CloudAccount> accountOpt = cloudAccountRepository.findByGcpProjectId(gcpProjectId);

        // Populate cluster info and node pools from GCP API
        populateFromGcpApi(dto, gcpProjectId, clusterName, location);

        // Populate Prometheus data if connected
        if (accountOpt.isPresent()) {
            CloudAccount account = accountOpt.get();
            Optional<KubernetesCluster> k8sClusterOpt =
                    kubernetesClusterRepository.findByCloudAccount_IdAndClusterName(account.getId(), clusterName);

            if (k8sClusterOpt.isPresent() && k8sClusterOpt.get().getPrometheusUrl() != null
                    && !k8sClusterOpt.get().getPrometheusUrl().isBlank()) {
                String promUrl = k8sClusterOpt.get().getPrometheusUrl();
                log.info("Cluster {} is connected with Prometheus at {}", clusterName, promUrl);
                dto.setConnected(true);
                populateFromPrometheus(dto, promUrl, clusterName);
            } else {
                log.info("Cluster {} is not connected (no Prometheus URL)", clusterName);
                dto.setConnected(false);
            }
        }

        return dto;
    }

    /**
     * Saves (or clears) the Prometheus URL for a GKE cluster, creating the KubernetesCluster
     * record if it does not yet exist. Pass null to disconnect the cluster.
     */
    public void savePrometheusUrl(String gcpProjectId, String clusterName, String prometheusUrl) {
        CloudAccount account = cloudAccountRepository.findByGcpProjectId(gcpProjectId)
                .orElseThrow(() -> new RuntimeException("GCP account not found for project: " + gcpProjectId));

        KubernetesCluster cluster = kubernetesClusterRepository
                .findByCloudAccount_IdAndClusterName(account.getId(), clusterName)
                .orElse(new KubernetesCluster(clusterName, account, prometheusUrl));

        cluster.setPrometheusUrl(prometheusUrl);
        kubernetesClusterRepository.save(cluster);
        log.info("Prometheus URL for cluster {} set to: {}", clusterName, prometheusUrl);
    }

    private void populateFromGcpApi(GkeDashboardDto dto, String projectId, String clusterName, String location) {
        Optional<ClusterManagerClient> clientOpt = gcpClientProvider.getClusterManagerClient(projectId);
        if (clientOpt.isEmpty()) {
            log.warn("Could not obtain ClusterManagerClient for project {}", projectId);
            dto.setNodePools(Collections.emptyList());
            return;
        }

        try (ClusterManagerClient client = clientOpt.get()) {
            // GCP cluster name format: projects/{project}/locations/{location}/clusters/{name}
            String clusterPath = "projects/" + projectId + "/locations/" + location + "/clusters/" + clusterName;
            Cluster cluster = client.getCluster(clusterPath);

            dto.setStatus(cluster.getStatus().name());
            dto.setVersion(cluster.getCurrentMasterVersion());
            dto.setEndpoint(cluster.getEndpoint());

            List<GkeNodePoolDto> nodePools = cluster.getNodePoolsList().stream()
                    .map(this::mapNodePool)
                    .collect(Collectors.toList());
            dto.setNodePools(nodePools);

        } catch (Exception e) {
            log.error("Error fetching GKE cluster details for {}/{}: {}", projectId, clusterName, e.getMessage());
            dto.setNodePools(Collections.emptyList());
        }
    }

    private GkeNodePoolDto mapNodePool(NodePool pool) {
        GkeNodePoolDto np = new GkeNodePoolDto();
        np.setName(pool.getName());
        np.setStatus(pool.getStatus().name());
        np.setNodeCount(pool.getInitialNodeCount());
        np.setVersion(pool.getVersion());

        if (pool.hasConfig()) {
            np.setMachineType(pool.getConfig().getMachineType());
            np.setDiskSizeGb(String.valueOf(pool.getConfig().getDiskSizeGb()));
            np.setImageType(pool.getConfig().getImageType());
        }

        if (pool.hasAutoscaling() && pool.getAutoscaling().getEnabled()) {
            np.setAutoscalingEnabled(true);
            np.setMinNodes(pool.getAutoscaling().getMinNodeCount());
            np.setMaxNodes(pool.getAutoscaling().getMaxNodeCount());
        }

        return np;
    }

    private void populateFromPrometheus(GkeDashboardDto dto, String promUrl, String clusterName) {
        try {
            dto.setOverview(buildOverview(promUrl, clusterName));
            dto.setWorkloadHealth(buildWorkloadHealth(promUrl, clusterName));
            dto.setResourceUtilization(buildResourceUtilization(promUrl, clusterName));
            dto.setNodeMetrics(buildNodeMetrics(promUrl, clusterName));
            dto.setContainerMetrics(buildContainerMetrics(promUrl, clusterName));
            dto.setCostMetrics(buildCostMetrics(promUrl, clusterName));
            dto.setSecurityMetrics(buildSecurityMetrics(promUrl, clusterName));
        } catch (Exception e) {
            log.error("Error fetching Prometheus data for cluster {}: {}", clusterName, e.getMessage());
        }
    }

    private GkeResourceUtilizationDto buildResourceUtilization(String promUrl, String clusterName) {
        GkeResourceUtilizationDto util = new GkeResourceUtilizationDto();
        try {
            List<Map<String, String>> nodeInfoList = prometheusService.getNodeInfo(promUrl, clusterName);
            Map<String, Double> cpuUsage = safeMap(prometheusService.getNodeCpuUsage(promUrl, clusterName));
            Map<String, Double> memUsage = safeMap(prometheusService.getNodeMemoryUsage(promUrl, clusterName));
            Map<String, Double> cpuReq = safeMap(prometheusService.getNodeCpuRequests(promUrl, clusterName));
            Map<String, Double> memReq = safeMap(prometheusService.getNodeMemoryRequests(promUrl, clusterName));
            Map<String, Map<String, Double>> capacity = prometheusService.getNodeCapacity(promUrl, clusterName);
            if (capacity == null) capacity = Collections.emptyMap();

            List<GkeNodeResourceDto> nodes = new ArrayList<>();
            for (Map<String, String> info : nodeInfoList) {
                String nodeName = info.getOrDefault("node", info.getOrDefault("name", ""));
                Map<String, Double> cap = capacity.getOrDefault(nodeName, Collections.emptyMap());
                double cpuCap = cap.getOrDefault("cpu", 1.0) * 1000;
                double memCapBytes = cap.getOrDefault("memory", 1.0);

                GkeNodeResourceDto n = new GkeNodeResourceDto();
                n.setNodeName(nodeName);
                double cpuPct = cpuUsage.getOrDefault(nodeName, 0.0);           // already 0-100 from PromQL * 100
                double memPct = memUsage.getOrDefault(nodeName, 0.0);           // already 0-100 from PromQL * 100
                n.setCpuUsagePercent(cpuPct);
                n.setMemoryUsagePercent(memPct);
                n.setCpuUsageMillicores(cpuCap > 0 ? cpuPct * cpuCap / 100 : 0); // derive from pct × capacity
                n.setCpuCapacityMillicores(cpuCap);
                n.setCpuRequestsPercent(cpuCap > 0 ? cpuReq.getOrDefault(nodeName, 0.0) / cpuCap * 100 : 0); // cpuReq already in millicores
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

    private GkeNodeMetricsDto buildNodeMetrics(String promUrl, String clusterName) {
        GkeNodeMetricsDto nodeMetrics = new GkeNodeMetricsDto();
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
                    String n = lbl.getOrDefault("node", "");
                    labelMap.put(n, lbl);
                }
            }

            List<GkeNodeInfoDto> nodes = new ArrayList<>();
            for (Map<String, String> info : nodeInfoList) {
                String nodeName = info.getOrDefault("node", info.getOrDefault("name", ""));
                String condition = conditions.getOrDefault(nodeName, "Ready");
                Map<String, Double> cap = capacity.getOrDefault(nodeName, Collections.emptyMap());
                Map<String, String> lbl = labelMap.getOrDefault(nodeName, Collections.emptyMap());

                GkeNodeInfoDto n = new GkeNodeInfoDto();
                n.setName(nodeName);
                n.setReady("Ready".equalsIgnoreCase(condition));
                n.setDiskPressure("DiskPressure".equalsIgnoreCase(condition));
                n.setMemoryPressure("MemoryPressure".equalsIgnoreCase(condition));
                n.setInstanceType(info.getOrDefault("label_beta_kubernetes_io_instance_type",
                        lbl.getOrDefault("label_node_kubernetes_io_instance_type", "")));
                n.setZone(lbl.getOrDefault("label_topology_kubernetes_io_zone",
                        lbl.getOrDefault("label_failure_domain_beta_kubernetes_io_zone", "")));
                n.setNodePool(lbl.getOrDefault("label_cloud_google_com_gke_nodepool", ""));
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

    private GkeContainerMetricsDto buildContainerMetrics(String promUrl, String clusterName) {
        GkeContainerMetricsDto containerMetrics = new GkeContainerMetricsDto();
        try {
            List<Map<String, String>> podInfoList = prometheusService.getPodInfo(promUrl, clusterName);
            Map<String, Double> cpuUsage = safeMap(prometheusService.getPodCpuUsage(promUrl, clusterName));
            Map<String, Double> memUsage = safeMap(prometheusService.getPodMemoryUsage(promUrl, clusterName));
            Map<String, Double> restarts = safeMap(prometheusService.getPodRestartCount(promUrl, clusterName));

            List<GkeContainerDto> containers = new ArrayList<>();
            for (Map<String, String> info : podInfoList) {
                String podName = info.getOrDefault("pod", info.getOrDefault("name", ""));
                String namespace = info.getOrDefault("namespace", "");
                String phase = info.getOrDefault("phase", "Unknown");

                GkeContainerDto c = new GkeContainerDto();
                c.setName(podName);       // use pod as container proxy (container-level label not always present)
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

    private GkeCostMetricsDto buildCostMetrics(String promUrl, String clusterName) {
        GkeCostMetricsDto cost = new GkeCostMetricsDto();
        try {
            // Cluster-level cost from Prometheus custom metric (or estimate from resources)
            Double rawDailyCost = prometheusService.getClusterDailyCost(promUrl, clusterName);
            cost.setDailyCost(rawDailyCost != null ? rawDailyCost : 0.0);

            // Namespace costs
            Map<String, Double> nsCpu = safeMap(prometheusService.getNamespaceCpuUsage(promUrl, clusterName));
            Map<String, Double> nsMem = safeMap(prometheusService.getNamespaceMemoryUsage(promUrl, clusterName));
            Map<String, Double> nsPods = safeMap(prometheusService.getNamespacePodCount(promUrl, clusterName));
            List<GkeNamespaceCostDto> nsCosts = new ArrayList<>();
            Set<String> namespaces = new LinkedHashSet<>(nsCpu.keySet());
            namespaces.addAll(nsMem.keySet());
            for (String ns : namespaces) {
                // Values are already in millicores (CPU) and MB (memory) from PromQL — no extra conversion
                double cpuMilli = nsCpu.getOrDefault(ns, 0.0);
                double memMb = nsMem.getOrDefault(ns, 0.0);
                double estDaily = estimateDailyCost(cpuMilli, memMb);
                GkeNamespaceCostDto nc = new GkeNamespaceCostDto(ns, cpuMilli, memMb,
                        nsPods.getOrDefault(ns, 0.0).intValue(), estDaily, estDaily * 30);
                nsCosts.add(nc);
            }
            nsCosts.sort((a, b) -> Double.compare(b.getDailyCost(), a.getDailyCost()));
            cost.setNamespaceCosts(nsCosts);

            // If no custom cost metric, derive total from namespace estimates
            if (cost.getDailyCost() == 0.0 && !nsCosts.isEmpty()) {
                cost.setDailyCost(nsCosts.stream()
                        .mapToDouble(GkeNamespaceCostDto::getDailyCost).sum());
            }

            // Workload costs from deployment CPU/memory
            List<GkeWorkloadCostDto> workloadCosts = new ArrayList<>();
            try {
                Map<String, Map<String, Double>> deplCpu = prometheusService.getDeploymentCpuUsage(promUrl, clusterName);
                Map<String, Map<String, Double>> deplMem = prometheusService.getDeploymentMemoryUsage(promUrl, clusterName);
                if (deplCpu != null) {
                    for (Map.Entry<String, Map<String, Double>> e : deplCpu.entrySet()) {
                        String deplKey = e.getKey();
                        String[] parts = deplKey.split("/", 2);
                        String ns = parts.length > 1 ? parts[0] : "";
                        String name = parts.length > 1 ? parts[1] : deplKey;
                        // Values already in millicores (CPU) and MB (memory) from PromQL — no extra conversion
                        double cpuMilli = e.getValue().values().stream().mapToDouble(Double::doubleValue).sum();
                        double memMb = 0;
                        if (deplMem != null && deplMem.containsKey(deplKey)) {
                            memMb = deplMem.get(deplKey).values().stream().mapToDouble(Double::doubleValue).sum();
                        }
                        double estDaily = estimateDailyCost(cpuMilli, memMb);
                        workloadCosts.add(new GkeWorkloadCostDto(name, ns, "Deployment", cpuMilli, memMb, 0, estDaily, estDaily * 30));
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

    private GkeSecurityMetricsDto buildSecurityMetrics(String promUrl, String clusterName) {
        GkeSecurityMetricsDto sec = new GkeSecurityMetricsDto();
        sec.setEventsByPriority(Collections.emptyMap());
        try {
            Double total = prometheusService.getFalcoEventsTotal(promUrl, clusterName);
            sec.setTotalEvents(total != null ? total : 0.0);

            List<Map<String, String>> byPriority = prometheusService.getFalcoEventsByPriority(promUrl, clusterName);
            if (byPriority != null) {
                Map<String, Integer> priorityMap = new LinkedHashMap<>();
                for (Map<String, String> entry : byPriority) {
                    String priority = entry.getOrDefault("priority", "unknown");
                    int count = parseInt(entry.getOrDefault("value", entry.getOrDefault("count", "0")));
                    priorityMap.merge(priority, count, Integer::sum);
                }
                sec.setEventsByPriority(priorityMap);
            }
        } catch (Exception e) {
            log.debug("Falco not available for cluster {}: {}", clusterName, e.getMessage());
        }

        // Populate individual alerts from getFalcoAlerts()
        List<GkeSecurityAlertDto> alerts = new ArrayList<>();
        try {
            List<Map<String, String>> rawAlerts = prometheusService.getFalcoAlerts(promUrl, clusterName);
            for (Map<String, String> a : rawAlerts) {
                GkeSecurityAlertDto alert = new GkeSecurityAlertDto();
                alert.setRule(a.getOrDefault("rule", "Unknown"));
                alert.setPriority(a.getOrDefault("priority", "unknown"));
                alert.setCount(parseInt(a.getOrDefault("value", a.getOrDefault("count", "1"))));
                alert.setNamespace(a.getOrDefault("k8s_ns_name", a.getOrDefault("namespace", "")));
                alert.setLastSeen("recent");
                alerts.add(alert);
            }
        } catch (Exception ignored) {}
        sec.setAlerts(alerts);

        // Detect if Falco pods are running (independent of whether scraping is configured)
        try {
            sec.setFalcoRunning(prometheusService.isFalcoRunning(promUrl));
        } catch (Exception ignored) {}

        // ---- Kubescape ----
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

        // ---- Trivy Operator ----
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

    /** Estimate daily cost from CPU millicores and memory MB (GCP e2 approximate pricing) */
    private double estimateDailyCost(double cpuMillicores, double memoryMb) {
        return (cpuMillicores / 1000.0) * 0.048 * 24 + (memoryMb / 1024.0) * 0.006 * 24;
    }

    private GkeOverviewDto buildOverview(String promUrl, String clusterName) {
        GkeOverviewDto overview = new GkeOverviewDto();

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

    private GkeWorkloadHealthDto buildWorkloadHealth(String promUrl, String clusterName) {
        GkeWorkloadHealthDto health = new GkeWorkloadHealthDto();

        // Pods
        health.setPods(buildPods(promUrl, clusterName));

        // Deployments
        health.setDeployments(buildDeployments(promUrl, clusterName));

        // StatefulSets
        health.setStatefulsets(buildStatefulsets(promUrl, clusterName));

        // DaemonSets
        health.setDaemonsets(buildDaemonsets(promUrl, clusterName));

        // Health score: percentage of running pods
        List<GkePodDto> pods = health.getPods();
        if (!pods.isEmpty()) {
            long running = pods.stream().filter(p -> "Running".equalsIgnoreCase(p.getPhase())).count();
            health.setClusterHealthScore((double) running / pods.size() * 100);
        }

        return health;
    }

    private List<GkePodDto> buildPods(String promUrl, String clusterName) {
        try {
            List<Map<String, String>> podInfoList = prometheusService.getPodInfo(promUrl, clusterName);
            Map<String, String> podPhases = prometheusService.getPodStatusPhase(promUrl, clusterName);
            Map<String, Double> cpuUsage = safeMap(prometheusService.getPodCpuUsage(promUrl, clusterName));
            Map<String, Double> memUsage = safeMap(prometheusService.getPodMemoryUsage(promUrl, clusterName));
            Map<String, Double> restarts = safeMap(prometheusService.getPodRestartCount(promUrl, clusterName));
            Map<String, Double> cpuReq = safeMap(prometheusService.getPodCpuRequests(promUrl, clusterName));
            Map<String, Double> memReq = safeMap(prometheusService.getPodMemoryRequests(promUrl, clusterName));
            Map<String, Double> creationTimes = safeMap(prometheusService.getPodCreationTime(promUrl, clusterName));

            List<GkePodDto> result = new ArrayList<>();
            for (Map<String, String> info : podInfoList) {
                String podName = info.getOrDefault("pod", info.getOrDefault("name", ""));
                String namespace = info.getOrDefault("namespace", "");
                String phase = podPhases.getOrDefault(podName, info.getOrDefault("phase", "Unknown"));
                String node = info.getOrDefault("node", info.getOrDefault("node_name", ""));

                GkePodDto pod = new GkePodDto();
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

    private List<GkeDeploymentDto> buildDeployments(String promUrl, String clusterName) {
        try {
            List<Map<String, String>> deployList = prometheusService.getClusterDeployments(promUrl, clusterName);
            Map<String, Double> available = safeMap(prometheusService.getDeploymentAvailableReplicas(promUrl, clusterName));
            Map<String, Double> desired = safeMap(prometheusService.getDeploymentSpecReplicas(promUrl, clusterName));

            List<GkeDeploymentDto> result = new ArrayList<>();
            for (Map<String, String> d : deployList) {
                String name = d.getOrDefault("deployment", d.getOrDefault("name", ""));
                String namespace = d.getOrDefault("namespace", "");
                int desiredCount = desired.getOrDefault(name, 0.0).intValue();
                int availableCount = available.getOrDefault(name, 0.0).intValue();

                GkeDeploymentDto dep = new GkeDeploymentDto();
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

    private List<GkeWorkloadDto> buildStatefulsets(String promUrl, String clusterName) {
        try {
            List<Map<String, String>> list = prometheusService.getClusterStatefulsets(promUrl, clusterName);
            return list.stream().map(ss -> {
                GkeWorkloadDto w = new GkeWorkloadDto();
                w.setName(ss.getOrDefault("statefulset", ss.getOrDefault("name", "")));
                w.setNamespace(ss.getOrDefault("namespace", ""));
                w.setKind("StatefulSet");
                w.setReplicas(parseInt(ss.get("replicas_desired")));
                w.setReadyReplicas(parseInt(ss.get("replicas_ready")));
                return w;
            }).collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("Error building statefulsets for {}: {}", clusterName, e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<GkeWorkloadDto> buildDaemonsets(String promUrl, String clusterName) {
        try {
            List<Map<String, String>> list = prometheusService.getClusterDaemonsets(promUrl, clusterName);
            return list.stream().map(ds -> {
                GkeWorkloadDto w = new GkeWorkloadDto();
                w.setName(ds.getOrDefault("daemonset", ds.getOrDefault("name", "")));
                w.setNamespace(ds.getOrDefault("namespace", ""));
                w.setKind("DaemonSet");
                w.setReplicas(parseInt(ds.get("desired_number_scheduled")));
                w.setReadyReplicas(parseInt(ds.get("number_ready")));
                return w;
            }).collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("Error building daemonsets for {}: {}", clusterName, e.getMessage());
            return Collections.emptyList();
        }
    }

    // --- Helpers ---

    private String computeAge(long epochSeconds) {
        long now = System.currentTimeMillis() / 1000;
        long diff = now - epochSeconds;
        if (diff < 3600) return (diff / 60) + "m";
        if (diff < 86400) return (diff / 3600) + "h";
        return (diff / 86400) + "d";
    }

    private int safeInt(Integer v) { return v == null ? 0 : v; }
    private double safeDouble(Double v) { return v == null ? 0.0 : v; }
    private int parseInt(String s) { try { return s == null ? 0 : (int) Double.parseDouble(s); } catch (Exception e) { return 0; } }

    private <K, V> Map<K, V> safeMap(Map<K, V> m) { return m == null ? Collections.emptyMap() : m; }
}
