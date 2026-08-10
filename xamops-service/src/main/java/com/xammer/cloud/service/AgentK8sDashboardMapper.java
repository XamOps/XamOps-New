package com.xammer.cloud.service;

import com.xammer.cloud.dto.gcp.GkeDashboardDto;
import com.xammer.cloud.dto.k8s.KubescapeDashboard;
import com.xammer.cloud.dto.k8s.TrivyDashboard;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps XamOps agent data ("k8s-resources" / "prometheus-metrics" batches) into the
 * GKE-shaped dashboard DTO sections that both {@code AksDashboardDto} and
 * {@code GkeDashboardDto} expose.
 *
 * <p>Why this exists: {@code EksDashboardService} already consumes agent data, but it
 * writes into {@code EksCompleteDashboardDto}, a different shape. AKS and GKE share the
 * Gke* nested DTOs and previously built them <em>only</em> from direct PromQL calls,
 * which required {@code kubernetes_cluster.prometheus_url} to be set and the XamOps
 * server to be able to reach the cluster's Prometheus inbound. Agent-onboarded clusters
 * have neither, so their dashboards stayed empty even while the agent pushed valid data
 * every interval. This mapper closes that gap without touching the Prometheus path.
 *
 * <p>All input comes from raw {@code Map<String, Object>} lists as parsed by
 * {@link AgentDataTransformerService}; the key names below match the agent's JSON tags
 * in {@code xamops-agent/internal/collector/types.go}.
 */
@Service
public class AgentK8sDashboardMapper {

    // ─────────────────────────────────────────────────────────────────────────
    // Overview
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Cluster-level counts and CPU/memory totals.
     *
     * <p>Capacity is summed from each node's {@code capacityCpu}/{@code capacityMemory}.
     * Usage prefers the agent's {@code nodeMetrics} (metrics-server derived); when that
     * is absent it falls back to the cluster-wide percentages in the prometheus-metrics
     * batch, applied to the known capacity.
     */
    public GkeDashboardDto.GkeOverviewDto buildOverview(
            AgentDataTransformerService.K8sResourcesResult k8s,
            AgentDataTransformerService.PrometheusMetricsResult prom) {

        GkeDashboardDto.GkeOverviewDto dto = new GkeDashboardDto.GkeOverviewDto();
        if (k8s == null) return dto;

        dto.setTotalNodes(k8s.getTotalNodes());
        dto.setTotalPods(k8s.getTotalPods());
        dto.setTotalNamespaces(k8s.getTotalNamespaces());

        double cpuCapacityMillis = 0d;
        double memCapacityBytes = 0d;
        for (Map<String, Object> node : safe(k8s.getNodes())) {
            cpuCapacityMillis += parseCpuToMillicores(str(node, "capacityCpu"));
            memCapacityBytes += parseMemoryToBytes(str(node, "capacityMemory"));
        }
        dto.setCpuCapacityMillicores(cpuCapacityMillis);
        dto.setMemoryCapacityBytes(memCapacityBytes);

        double cpuUsageMillis = 0d;
        double memUsageBytes = 0d;
        boolean haveNodeMetrics = false;
        for (Map<String, Object> nm : safe(k8s.getNodeMetrics())) {
            haveNodeMetrics = true;
            cpuUsageMillis += parseCpuToMillicores(str(nm, "cpuUsage"));
            memUsageBytes += parseMemoryToBytes(str(nm, "memoryUsage"));
        }

        if (!haveNodeMetrics && prom != null) {
            // No metrics-server data — derive absolute usage from cluster percentages.
            cpuUsageMillis = cpuCapacityMillis * (prom.getClusterCpuUsagePct() / 100d);
            memUsageBytes = memCapacityBytes * (prom.getClusterMemUsagePct() / 100d);
        }

        dto.setCpuUsageMillicores(cpuUsageMillis);
        dto.setMemoryUsageBytes(memUsageBytes);
        dto.setCpuUsagePercent(percent(cpuUsageMillis, cpuCapacityMillis));
        dto.setMemoryUsagePercent(percent(memUsageBytes, memCapacityBytes));
        return dto;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Workload health
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Pods, deployments, statefulsets and daemonsets, plus a health score defined as
     * the share of pods in the Running phase.
     */
    public GkeDashboardDto.GkeWorkloadHealthDto buildWorkloadHealth(
            AgentDataTransformerService.K8sResourcesResult k8s) {

        GkeDashboardDto.GkeWorkloadHealthDto dto = new GkeDashboardDto.GkeWorkloadHealthDto();
        if (k8s == null) return dto;

        List<GkeDashboardDto.GkePodDto> pods = new ArrayList<>();
        for (Map<String, Object> p : safe(k8s.getPods())) {
            GkeDashboardDto.GkePodDto pod = new GkeDashboardDto.GkePodDto();
            pod.setName(str(p, "name"));
            pod.setNamespace(str(p, "namespace"));
            pod.setPhase(str(p, "phase"));
            pod.setNodeName(str(p, "nodeName"));
            pod.setAge(str(p, "age"));
            pod.setPodIp(str(p, "podIp"));
            pod.setOwnerKind(str(p, "controllerKind"));
            pod.setOwnerName(str(p, "controllerName"));

            // Restarts and requests are per-container on the agent payload; aggregate.
            int restarts = 0;
            double cpuRequest = 0d;
            double memRequest = 0d;
            for (Map<String, Object> c : containersOf(p)) {
                restarts += intOf(c, "restartCount");
                cpuRequest += parseCpuToMillicores(str(c, "cpuRequest"));
                memRequest += parseMemoryToBytes(str(c, "memoryRequest"));
            }
            pod.setRestartCount(restarts);
            pod.setCpuRequest(cpuRequest);
            pod.setMemoryRequest(memRequest);
            pods.add(pod);
        }
        applyPodUsage(pods, k8s.getPodMetrics());
        dto.setPods(pods);

        List<GkeDashboardDto.GkeDeploymentDto> deployments = new ArrayList<>();
        for (Map<String, Object> d : safe(k8s.getDeployments())) {
            GkeDashboardDto.GkeDeploymentDto dep = new GkeDashboardDto.GkeDeploymentDto();
            dep.setName(str(d, "name"));
            dep.setNamespace(str(d, "namespace"));
            dep.setReplicas(intOf(d, "replicas"));
            dep.setReadyReplicas(intOf(d, "readyReplicas"));
            dep.setAvailableReplicas(intOf(d, "availableReplicas"));
            deployments.add(dep);
        }
        dto.setDeployments(deployments);

        List<GkeDashboardDto.GkeWorkloadDto> statefulsets = new ArrayList<>();
        for (Map<String, Object> s : safe(k8s.getStatefulSets())) {
            GkeDashboardDto.GkeWorkloadDto w = new GkeDashboardDto.GkeWorkloadDto();
            w.setName(str(s, "name"));
            w.setNamespace(str(s, "namespace"));
            w.setReplicas(intOf(s, "replicas"));
            w.setReadyReplicas(intOf(s, "readyReplicas"));
            w.setKind("StatefulSet");
            statefulsets.add(w);
        }
        dto.setStatefulsets(statefulsets);

        List<GkeDashboardDto.GkeWorkloadDto> daemonsets = new ArrayList<>();
        for (Map<String, Object> s : safe(k8s.getDaemonSets())) {
            GkeDashboardDto.GkeWorkloadDto w = new GkeDashboardDto.GkeWorkloadDto();
            w.setName(str(s, "name"));
            w.setNamespace(str(s, "namespace"));
            // DaemonSets have no replicas field — desired/ready scheduled counts map cleanly.
            w.setReplicas(intOf(s, "desiredNumberScheduled"));
            w.setReadyReplicas(intOf(s, "numberReady"));
            w.setKind("DaemonSet");
            daemonsets.add(w);
        }
        dto.setDaemonsets(daemonsets);

        dto.setClusterHealthScore(percent(k8s.getRunningPods(), k8s.getTotalPods()));
        return dto;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Node metrics / resource utilization / container metrics
    // ─────────────────────────────────────────────────────────────────────────

    /** Per-node conditions, instance info and usage. */
    public GkeDashboardDto.GkeNodeMetricsDto buildNodeMetrics(
            AgentDataTransformerService.K8sResourcesResult k8s) {

        GkeDashboardDto.GkeNodeMetricsDto dto = new GkeDashboardDto.GkeNodeMetricsDto();
        if (k8s == null) return dto;

        List<GkeDashboardDto.GkeNodeInfoDto> nodes = new ArrayList<>();
        for (Map<String, Object> n : safe(k8s.getNodes())) {
            GkeDashboardDto.GkeNodeInfoDto node = new GkeDashboardDto.GkeNodeInfoDto();
            String name = str(n, "name");
            node.setName(name);
            node.setInstanceType(str(n, "instanceType"));
            node.setZone(str(n, "zone"));
            node.setInternalIp(str(n, "internalIp"));
            node.setKubeletVersion(str(n, "kubeletVersion"));
            node.setNodePool(nodePoolOf(n));
            node.setCpuCapacity(parseCpuToMillicores(str(n, "capacityCpu")));
            node.setMemoryCapacity(parseMemoryToBytes(str(n, "capacityMemory")));

            // Conditions arrive as a list of {type,status}; Ready=True is healthy,
            // whereas DiskPressure/MemoryPressure=True are problems.
            for (Map<String, Object> cond : conditionsOf(n)) {
                String type = str(cond, "type");
                boolean isTrue = "True".equalsIgnoreCase(str(cond, "status"));
                if ("Ready".equalsIgnoreCase(type)) node.setReady(isTrue);
                else if ("DiskPressure".equalsIgnoreCase(type)) node.setDiskPressure(isTrue);
                else if ("MemoryPressure".equalsIgnoreCase(type)) node.setMemoryPressure(isTrue);
            }

            Map<String, Object> nm = findNodeMetric(k8s.getNodeMetrics(), name);
            if (nm != null) {
                node.setCpuUsageMillicores(parseCpuToMillicores(str(nm, "cpuUsage")));
                // cpuUsage/memoryUsage on this DTO are PERCENTAGES (0-100), matching what
                // the Prometheus path stores via PrometheusService.getNodeCpuUsage (its
                // query ends in "* 100"). Absolute values belong in cpuUsageMillicores and
                // memoryCapacity.
                node.setCpuUsage(doubleOf(nm, "cpuPercent"));
                node.setMemoryUsage(doubleOf(nm, "memoryPercent"));
            }
            node.setPodCount(countPodsOnNode(k8s.getPods(), name));
            nodes.add(node);
        }
        dto.setNodes(nodes);
        return dto;
    }

    /** Per-node utilization percentages, including requests-vs-capacity pressure. */
    public GkeDashboardDto.GkeResourceUtilizationDto buildResourceUtilization(
            AgentDataTransformerService.K8sResourcesResult k8s) {

        GkeDashboardDto.GkeResourceUtilizationDto dto = new GkeDashboardDto.GkeResourceUtilizationDto();
        if (k8s == null) return dto;

        List<GkeDashboardDto.GkeNodeResourceDto> nodes = new ArrayList<>();
        for (Map<String, Object> n : safe(k8s.getNodes())) {
            String name = str(n, "name");
            GkeDashboardDto.GkeNodeResourceDto r = new GkeDashboardDto.GkeNodeResourceDto();
            r.setNodeName(name);

            double cpuCapacity = parseCpuToMillicores(str(n, "capacityCpu"));
            double memCapacity = parseMemoryToBytes(str(n, "capacityMemory"));
            r.setCpuCapacityMillicores(cpuCapacity);

            Map<String, Object> nm = findNodeMetric(k8s.getNodeMetrics(), name);
            if (nm != null) {
                r.setCpuUsageMillicores(parseCpuToMillicores(str(nm, "cpuUsage")));
                r.setCpuUsagePercent(doubleOf(nm, "cpuPercent"));
                r.setMemoryUsagePercent(doubleOf(nm, "memoryPercent"));
            }

            // Scheduled requests on this node, as a share of capacity.
            double cpuRequests = 0d;
            double memRequests = 0d;
            for (Map<String, Object> p : safe(k8s.getPods())) {
                if (!name.equals(str(p, "nodeName"))) continue;
                for (Map<String, Object> c : containersOf(p)) {
                    cpuRequests += parseCpuToMillicores(str(c, "cpuRequest"));
                    memRequests += parseMemoryToBytes(str(c, "memoryRequest"));
                }
            }
            r.setCpuRequestsPercent(percent(cpuRequests, cpuCapacity));
            r.setMemoryRequestsPercent(percent(memRequests, memCapacity));
            nodes.add(r);
        }
        dto.setNodes(nodes);
        return dto;
    }

    /** Flattens every pod's containers into a single list. */
    public GkeDashboardDto.GkeContainerMetricsDto buildContainerMetrics(
            AgentDataTransformerService.K8sResourcesResult k8s) {

        GkeDashboardDto.GkeContainerMetricsDto dto = new GkeDashboardDto.GkeContainerMetricsDto();
        if (k8s == null) return dto;

        List<GkeDashboardDto.GkeContainerDto> containers = new ArrayList<>();
        for (Map<String, Object> p : safe(k8s.getPods())) {
            String podName = str(p, "name");
            String namespace = str(p, "namespace");
            for (Map<String, Object> c : containersOf(p)) {
                GkeDashboardDto.GkeContainerDto dtoC = new GkeDashboardDto.GkeContainerDto();
                dtoC.setName(str(c, "name"));
                dtoC.setPod(podName);
                dtoC.setNamespace(namespace);
                dtoC.setImage(str(c, "image"));
                dtoC.setState(str(c, "state"));
                dtoC.setReady(boolOf(c, "ready"));
                dtoC.setRestartCount(intOf(c, "restartCount"));
                Map<String, Object> usage = containerUsage(k8s.getPodMetrics(), namespace, podName, str(c, "name"));
                // cpuUsage is millicores and memoryUsage is bytes here, matching the
                // Prometheus path (getContainerCpuUsage multiplies by 1000).
                dtoC.setCpuUsage(parseCpuToMillicores(str(usage, "cpuUsage")));
                dtoC.setMemoryUsage(parseMemoryToBytes(str(usage, "memoryUsage")));
                containers.add(dtoC);
            }
        }
        dto.setContainers(containers);
        return dto;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Security metrics (Falco + Kubescape + Trivy)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Combines the three security sources the agent collects.
     *
     * <p>The {@code *Running} flags drive the frontend's "X Not Installed" empty states, so
     * they are set from whether that tool's batch actually produced findings — not from
     * whether the section as a whole exists. Previously the whole section was null, which
     * made all three read "Not Installed" even on a cluster running all three.
     *
     * <p>Any of the three inputs may be null; each contributes independently.
     */
    public GkeDashboardDto.GkeSecurityMetricsDto buildSecurityMetrics(
            TrivyDashboard trivy,
            KubescapeDashboard kubescape,
            AgentDataTransformerService.PrometheusMetricsResult prom) {

        GkeDashboardDto.GkeSecurityMetricsDto dto = new GkeDashboardDto.GkeSecurityMetricsDto();
        applyFalco(dto, prom);
        applyKubescape(dto, kubescape);
        applyTrivy(dto, trivy);
        return dto;
    }

    private void applyFalco(GkeDashboardDto.GkeSecurityMetricsDto dto,
                            AgentDataTransformerService.PrometheusMetricsResult prom) {
        Map<String, Integer> byPriority = new LinkedHashMap<>();
        List<GkeDashboardDto.GkeSecurityAlertDto> alerts = new ArrayList<>();
        double total = 0d;

        if (prom != null) {
            for (Map<String, Object> event : safe(prom.getFalcoEvents())) {
                int count = (int) Math.round(doubleOf(event, "count"));
                String priority = blankTo(str(event, "priority"), "Unknown");
                total += count;
                byPriority.merge(priority, count, Integer::sum);

                GkeDashboardDto.GkeSecurityAlertDto alert = new GkeDashboardDto.GkeSecurityAlertDto();
                alert.setRule(str(event, "rule"));
                alert.setPriority(priority);
                alert.setCount(count);
                alert.setNamespace(str(event, "namespace"));
                alert.setLastSeen(str(event, "collectedAt"));
                alerts.add(alert);
            }
        }

        dto.setTotalEvents(total);
        dto.setEventsByPriority(byPriority);
        dto.setAlerts(alerts);
        dto.setFalcoRunning(!alerts.isEmpty());
    }

    private void applyKubescape(GkeDashboardDto.GkeSecurityMetricsDto dto, KubescapeDashboard kubescape) {
        Map<String, Integer> failsBySeverity = new LinkedHashMap<>();
        List<GkeDashboardDto.GkeKubescapeFindingDto> findings = new ArrayList<>();

        if (kubescape == null) {
            dto.setKubescapeFailsBySeverity(failsBySeverity);
            dto.setKubescapeFindings(findings);
            return;
        }

        int critical = orZero(kubescape.getTotalCritical());
        int high = orZero(kubescape.getTotalHigh());
        int medium = orZero(kubescape.getTotalMedium());
        int low = orZero(kubescape.getTotalLow());
        if (critical > 0) failsBySeverity.put("Critical", critical);
        if (high > 0) failsBySeverity.put("High", high);
        if (medium > 0) failsBySeverity.put("Medium", medium);
        if (low > 0) failsBySeverity.put("Low", low);

        int totalFailed = critical + high + medium + low;
        dto.setKubescapeTotalFailed(totalFailed);
        dto.setKubescapeFailsBySeverity(failsBySeverity);

        // Passed count is not reported directly; derive it from the compliance score so
        // the tab can show a pass/fail split. score = passed / (passed + failed).
        Double score = kubescape.getOverallComplianceScore();
        if (score != null && score > 0d && score < 100d && totalFailed > 0) {
            dto.setKubescapeTotalPassed((int) Math.round(totalFailed * (score / (100d - score))));
        } else if (score != null && score >= 100d) {
            dto.setKubescapeTotalPassed(orZero(kubescape.getTotalWorkloads()));
        }

        // Per-workload failure counts are the closest thing to per-control findings that
        // the agent's compliance batch carries.
        if (kubescape.getVulnerabilities() != null) {
            for (KubescapeDashboard.VulnerabilitySummary v : kubescape.getVulnerabilities()) {
                int failed = orZero(v.getCriticalCount()) + orZero(v.getHighCount())
                        + orZero(v.getMediumCount()) + orZero(v.getLowCount());
                if (failed == 0) continue;
                GkeDashboardDto.GkeKubescapeFindingDto f = new GkeDashboardDto.GkeKubescapeFindingDto();
                f.setControlName(blankTo(v.getWorkloadName(), "Unknown workload"));
                f.setSeverity(dominantSeverity(orZero(v.getCriticalCount()), orZero(v.getHighCount()),
                        orZero(v.getMediumCount()), orZero(v.getLowCount())));
                f.setFailedResources(failed);
                findings.add(f);
            }
        }
        dto.setKubescapeFindings(findings);

        // A cluster can be fully compliant and still be running Kubescape, so presence of
        // a scan (score or workload count) is the signal — not the finding count.
        dto.setKubescapeRunning(score != null || orZero(kubescape.getTotalWorkloads()) > 0 || !findings.isEmpty());
    }

    private void applyTrivy(GkeDashboardDto.GkeSecurityMetricsDto dto, TrivyDashboard trivy) {
        Map<String, Integer> bySeverity = new LinkedHashMap<>();
        List<GkeDashboardDto.GkeTrivyFindingDto> findings = new ArrayList<>();

        if (trivy == null || trivy.getSummary() == null) {
            dto.setTrivyBySeverity(bySeverity);
            dto.setTrivyFindings(findings);
            return;
        }

        TrivyDashboard.Summary summary = trivy.getSummary();
        if (summary.getTotalCritical() > 0) bySeverity.put("Critical", summary.getTotalCritical());
        if (summary.getTotalHigh() > 0) bySeverity.put("High", summary.getTotalHigh());
        if (summary.getTotalMedium() > 0) bySeverity.put("Medium", summary.getTotalMedium());
        if (summary.getTotalLow() > 0) bySeverity.put("Low", summary.getTotalLow());

        dto.setTrivyBySeverity(bySeverity);
        dto.setTrivyTotalVulnerabilities(
                (double) (summary.getTotalCritical() + summary.getTotalHigh()
                        + summary.getTotalMedium() + summary.getTotalLow()));

        if (trivy.getWorkloads() != null) {
            for (TrivyDashboard.WorkloadVulnerability w : trivy.getWorkloads()) {
                // One row per severity present, so the tab can group by severity.
                addTrivyFinding(findings, w, "Critical", w.getCritical());
                addTrivyFinding(findings, w, "High", w.getHigh());
                addTrivyFinding(findings, w, "Medium", w.getMedium());
                addTrivyFinding(findings, w, "Low", w.getLow());
            }
        }
        dto.setTrivyFindings(findings);

        // A clean cluster still has scan reports, so count reports rather than findings.
        dto.setTrivyRunning(summary.getTotalVulnerabilityReports() > 0
                || summary.getUniqueImagesScanned() > 0
                || !findings.isEmpty());
    }

    private void addTrivyFinding(List<GkeDashboardDto.GkeTrivyFindingDto> out,
                                 TrivyDashboard.WorkloadVulnerability w, String severity, int count) {
        if (count <= 0) return;
        GkeDashboardDto.GkeTrivyFindingDto f = new GkeDashboardDto.GkeTrivyFindingDto();
        String image = blankTo(w.getImage(), w.getName());
        if (w.getTag() != null && !w.getTag().isBlank() && !image.contains(":")) {
            image = image + ":" + w.getTag();
        }
        f.setImage(image);
        f.setNamespace(w.getNamespace());
        f.setSeverity(severity);
        f.setCount(count);
        out.add(f);
    }

    private static String dominantSeverity(int critical, int high, int medium, int low) {
        if (critical > 0) return "Critical";
        if (high > 0) return "High";
        if (medium > 0) return "Medium";
        if (low > 0) return "Low";
        return "Unknown";
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers — usage lookups
    // ─────────────────────────────────────────────────────────────────────────

    private void applyPodUsage(List<GkeDashboardDto.GkePodDto> pods,
                               List<Map<String, Object>> podMetrics) {
        if (pods.isEmpty() || podMetrics == null || podMetrics.isEmpty()) return;
        for (GkeDashboardDto.GkePodDto pod : pods) {
            for (Map<String, Object> pm : podMetrics) {
                if (!pod.getName().equals(str(pm, "podName"))) continue;
                if (!pod.getNamespace().equals(str(pm, "namespace"))) continue;
                double cpu = 0d;
                double mem = 0d;
                for (Map<String, Object> cu : listOf(pm, "containers")) {
                    cpu += parseCpuToMillicores(str(cu, "cpuUsage"));
                    mem += parseMemoryToBytes(str(cu, "memoryUsage"));
                }
                pod.setCpuUsage(cpu);
                pod.setMemoryUsage(mem);
                break;
            }
        }
    }

    /** Locates one container's usage entry inside the podMetrics batch, or null. */
    private Map<String, Object> containerUsage(List<Map<String, Object>> podMetrics,
                                               String namespace, String podName, String containerName) {
        if (podMetrics == null) return null;
        for (Map<String, Object> pm : podMetrics) {
            if (!podName.equals(str(pm, "podName"))) continue;
            if (!namespace.equals(str(pm, "namespace"))) continue;
            for (Map<String, Object> cu : listOf(pm, "containers")) {
                if (containerName.equals(str(cu, "name"))) return cu;
            }
        }
        return null;
    }

    private Map<String, Object> findNodeMetric(List<Map<String, Object>> nodeMetrics, String nodeName) {
        if (nodeMetrics == null || nodeName == null) return null;
        for (Map<String, Object> nm : nodeMetrics) {
            if (nodeName.equals(str(nm, "nodeName"))) return nm;
        }
        return null;
    }

    private int countPodsOnNode(List<Map<String, Object>> pods, String nodeName) {
        if (pods == null || nodeName == null) return 0;
        int count = 0;
        for (Map<String, Object> p : pods) {
            if (nodeName.equals(str(p, "nodeName"))) count++;
        }
        return count;
    }

    /**
     * Node pool name from labels. AKS uses {@code agentpool}; GKE and EKS use the
     * standard {@code node.kubernetes.io/*} / cloud-provider labels.
     */
    @SuppressWarnings("unchecked")
    private String nodePoolOf(Map<String, Object> node) {
        Object raw = node.get("labels");
        if (!(raw instanceof Map)) return "";
        Map<String, Object> labels = (Map<String, Object>) raw;
        for (String key : new String[]{
                "agentpool",                                  // AKS
                "kubernetes.azure.com/agentpool",             // AKS (fully qualified)
                "cloud.google.com/gke-nodepool",              // GKE
                "eks.amazonaws.com/nodegroup"}) {             // EKS
            Object v = labels.get(key);
            if (v != null && !v.toString().isBlank()) return v.toString();
        }
        return "";
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers — Kubernetes quantity parsing
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Parses a Kubernetes CPU quantity into millicores.
     * Accepts {@code "250m"}, {@code "4"}, {@code "1500n"}, {@code "2500u"}.
     */
    double parseCpuToMillicores(String value) {
        if (value == null || value.isBlank()) return 0d;
        String v = value.trim();
        try {
            if (v.endsWith("m")) return Double.parseDouble(v.substring(0, v.length() - 1));
            if (v.endsWith("n")) return Double.parseDouble(v.substring(0, v.length() - 1)) / 1_000_000d;
            if (v.endsWith("u")) return Double.parseDouble(v.substring(0, v.length() - 1)) / 1_000d;
            return Double.parseDouble(v) * 1000d;
        } catch (NumberFormatException e) {
            return 0d;
        }
    }

    /**
     * Parses a Kubernetes memory quantity into bytes.
     * Accepts binary suffixes ({@code Ki, Mi, Gi, Ti, Pi}), decimal suffixes
     * ({@code K, M, G, T, P}) and plain byte counts.
     */
    double parseMemoryToBytes(String value) {
        if (value == null || value.isBlank()) return 0d;
        String v = value.trim();
        try {
            // Binary suffixes first — "Mi" must not be matched as "M".
            String[][] binary = {{"Ki", "1024"}, {"Mi", "1048576"}, {"Gi", "1073741824"},
                    {"Ti", "1099511627776"}, {"Pi", "1125899906842624"}};
            for (String[] pair : binary) {
                if (v.endsWith(pair[0])) {
                    return Double.parseDouble(v.substring(0, v.length() - 2)) * Double.parseDouble(pair[1]);
                }
            }
            String[][] decimal = {{"K", "1000"}, {"M", "1000000"}, {"G", "1000000000"},
                    {"T", "1000000000000"}, {"P", "1000000000000000"}};
            for (String[] pair : decimal) {
                if (v.endsWith(pair[0])) {
                    return Double.parseDouble(v.substring(0, v.length() - 1)) * Double.parseDouble(pair[1]);
                }
            }
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return 0d;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers — null-safe map access
    // ─────────────────────────────────────────────────────────────────────────

    private static double percent(double used, double total) {
        if (total <= 0d) return 0d;
        return Math.round((used / total) * 1000d) / 10d;
    }

    private static List<Map<String, Object>> safe(List<Map<String, Object>> list) {
        return list == null ? Collections.emptyList() : list;
    }

    private static String str(Map<String, Object> map, String key) {
        if (map == null) return "";
        Object v = map.get(key);
        return v == null ? "" : v.toString();
    }

    private static int intOf(Map<String, Object> map, String key) {
        if (map == null) return 0;
        Object v = map.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        try {
            return v == null ? 0 : Integer.parseInt(v.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double doubleOf(Map<String, Object> map, String key) {
        if (map == null) return 0d;
        Object v = map.get(key);
        if (v instanceof Number) return ((Number) v).doubleValue();
        try {
            return v == null ? 0d : Double.parseDouble(v.toString());
        } catch (NumberFormatException e) {
            return 0d;
        }
    }

    private static boolean boolOf(Map<String, Object> map, String key) {
        if (map == null) return false;
        Object v = map.get(key);
        if (v instanceof Boolean) return (Boolean) v;
        return v != null && Boolean.parseBoolean(v.toString());
    }

    private static List<Map<String, Object>> containersOf(Map<String, Object> pod) {
        return listOf(pod, "containers");
    }

    private static List<Map<String, Object>> conditionsOf(Map<String, Object> node) {
        return listOf(node, "conditions");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOf(Map<String, Object> map, String key) {
        if (map == null) return Collections.emptyList();
        Object raw = map.get(key);
        if (!(raw instanceof List)) return Collections.emptyList();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : (List<Object>) raw) {
            if (o instanceof Map) out.add((Map<String, Object>) o);
        }
        return out;
    }
}
