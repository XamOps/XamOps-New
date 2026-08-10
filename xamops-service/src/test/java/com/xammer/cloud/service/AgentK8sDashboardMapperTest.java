package com.xammer.cloud.service;

import com.xammer.cloud.dto.gcp.GkeDashboardDto;
import com.xammer.cloud.dto.k8s.KubescapeDashboard;
import com.xammer.cloud.dto.k8s.TrivyDashboard;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the mapping that lets an agent-onboarded AKS/GKE cluster populate its dashboard.
 *
 * <p>The payload shapes here mirror the agent's JSON tags in
 * {@code xamops-agent/internal/collector/types.go}. Node/pod values are modelled on a real
 * AKS cluster: two Standard_D4s_v3 nodes (4 vCPU / 16Gi) plus one Standard_D2s_v3.
 */
class AgentK8sDashboardMapperTest {

    private final AgentK8sDashboardMapper mapper = new AgentK8sDashboardMapper();

    // ── Quantity parsing ─────────────────────────────────────────────────────

    @Test
    void parsesCpuQuantitiesToMillicores() {
        assertEquals(250d, mapper.parseCpuToMillicores("250m"));
        assertEquals(4000d, mapper.parseCpuToMillicores("4"), "whole cores become millicores");
        assertEquals(2.5d, mapper.parseCpuToMillicores("2500u"), 0.0001);
        assertEquals(0.0015d, mapper.parseCpuToMillicores("1500n"), 0.000001);
    }

    @Test
    void parsesMemoryBinarySuffixesWithoutConfusingMiForM() {
        assertEquals(1024d, mapper.parseMemoryToBytes("1Ki"));
        assertEquals(1048576d, mapper.parseMemoryToBytes("1Mi"));
        assertEquals(17179869184d, mapper.parseMemoryToBytes("16Gi"));
        // "M" is decimal — must not be swallowed by the "Mi" branch.
        assertEquals(1000000d, mapper.parseMemoryToBytes("1M"));
        assertEquals(4096d, mapper.parseMemoryToBytes("4096"), "bare numbers are bytes");
    }

    @Test
    void returnsZeroForBlankAndMalformedQuantities() {
        assertEquals(0d, mapper.parseCpuToMillicores(null));
        assertEquals(0d, mapper.parseCpuToMillicores(""));
        assertEquals(0d, mapper.parseCpuToMillicores("not-a-number"));
        assertEquals(0d, mapper.parseMemoryToBytes(null));
        assertEquals(0d, mapper.parseMemoryToBytes("Gi"));
    }

    // ── Overview ─────────────────────────────────────────────────────────────

    @Test
    void overviewSumsCapacityAcrossNodesAndComputesPercentages() {
        AgentDataTransformerService.K8sResourcesResult k8s = sampleCluster();

        GkeDashboardDto.GkeOverviewDto overview = mapper.buildOverview(k8s, null);

        assertEquals(3, overview.getTotalNodes());
        assertEquals(4, overview.getTotalPods());
        assertEquals(2, overview.getTotalNamespaces());
        // 4 + 4 + 2 vCPU
        assertEquals(10000d, overview.getCpuCapacityMillicores());
        // 16 + 16 + 8 GiB
        assertEquals(42949672960d, overview.getMemoryCapacityBytes());
        // 500m + 800m + 200m
        assertEquals(1500d, overview.getCpuUsageMillicores());
        assertEquals(15.0d, overview.getCpuUsagePercent(), 0.05);
    }

    @Test
    void overviewFallsBackToClusterPercentagesWhenMetricsServerDataMissing() {
        AgentDataTransformerService.K8sResourcesResult k8s = sampleCluster();
        k8s.setNodeMetrics(new ArrayList<>()); // no metrics-server

        AgentDataTransformerService.PrometheusMetricsResult prom =
                new AgentDataTransformerService.PrometheusMetricsResult();
        prom.setClusterCpuUsagePct(25d);
        prom.setClusterMemUsagePct(50d);

        GkeDashboardDto.GkeOverviewDto overview = mapper.buildOverview(k8s, prom);

        assertEquals(2500d, overview.getCpuUsageMillicores(), 0.01, "25% of 10000m");
        assertEquals(21474836480d, overview.getMemoryUsageBytes(), 1d, "50% of 40Gi");
    }

    @Test
    void overviewHandlesMissingInputWithoutThrowing() {
        GkeDashboardDto.GkeOverviewDto overview = mapper.buildOverview(null, null);
        assertNotNull(overview);
        assertEquals(0, overview.getTotalNodes());
        assertEquals(0d, overview.getCpuUsagePercent(), "no divide-by-zero on empty capacity");
    }

    // ── Workload health ──────────────────────────────────────────────────────

    @Test
    void workloadHealthAggregatesPerContainerRestartsAndRequests() {
        GkeDashboardDto.GkeWorkloadHealthDto health = mapper.buildWorkloadHealth(sampleCluster());

        GkeDashboardDto.GkePodDto multi = health.getPods().stream()
                .filter(p -> "falco-bvd89".equals(p.getName()))
                .findFirst().orElseThrow();

        assertEquals(5, multi.getRestartCount(), "2 + 3 across two containers");
        assertEquals(150d, multi.getCpuRequest(), "100m + 50m");
        assertEquals("DaemonSet", multi.getOwnerKind());
        assertEquals("aks-systempool-000000", multi.getNodeName());
    }

    @Test
    void workloadHealthScoreIsShareOfRunningPods() {
        AgentDataTransformerService.K8sResourcesResult k8s = sampleCluster();
        k8s.setTotalPods(4);
        k8s.setRunningPods(3);

        assertEquals(75.0d, mapper.buildWorkloadHealth(k8s).getClusterHealthScore(), 0.05);
    }

    @Test
    void daemonSetsMapScheduledCountsOntoReplicaFields() {
        GkeDashboardDto.GkeWorkloadHealthDto health = mapper.buildWorkloadHealth(sampleCluster());

        GkeDashboardDto.GkeWorkloadDto ds = health.getDaemonsets().get(0);
        assertEquals("DaemonSet", ds.getKind());
        assertEquals(3, ds.getReplicas(), "desiredNumberScheduled");
        assertEquals(3, ds.getReadyReplicas(), "numberReady");
    }

    @Test
    void podUsageComesFromPodMetricsSummedAcrossContainers() {
        GkeDashboardDto.GkeWorkloadHealthDto health = mapper.buildWorkloadHealth(sampleCluster());

        GkeDashboardDto.GkePodDto pod = health.getPods().stream()
                .filter(p -> "falco-bvd89".equals(p.getName()))
                .findFirst().orElseThrow();

        assertEquals(45d, pod.getCpuUsage(), "30m + 15m");
    }

    // ── Node metrics ─────────────────────────────────────────────────────────

    @Test
    void nodeMetricsReadConditionsWithCorrectPolarity() {
        GkeDashboardDto.GkeNodeMetricsDto metrics = mapper.buildNodeMetrics(sampleCluster());

        GkeDashboardDto.GkeNodeInfoDto healthy = nodeNamed(metrics, "aks-systempool-000000");
        assertTrue(healthy.isReady(), "Ready=True means ready");
        assertFalse(healthy.isDiskPressure());

        GkeDashboardDto.GkeNodeInfoDto pressured = nodeNamed(metrics, "aks-userpool-000000");
        assertFalse(pressured.isReady(), "Ready=False means not ready");
        assertTrue(pressured.isDiskPressure(), "DiskPressure=True is a problem, not health");
    }

    @Test
    void nodeUsageFieldsArePercentagesNotAbsoluteValues() {
        // The Prometheus path stores percentages in cpuUsage/memoryUsage (its PromQL ends
        // in "* 100"). Emitting bytes here instead pegged the Nodes tab bars at 100%.
        GkeDashboardDto.GkeNodeInfoDto node =
                nodeNamed(mapper.buildNodeMetrics(sampleCluster()), "aks-systempool-000000");

        assertEquals(12.5d, node.getCpuUsage(), 0.01, "cpuUsage must be a percentage");
        assertEquals(12.5d, node.getMemoryUsage(), 0.01, "memoryUsage must be a percentage");
        assertTrue(node.getMemoryUsage() <= 100d, "a percentage can never exceed 100");
        assertEquals(500d, node.getCpuUsageMillicores(), "absolute CPU belongs here");
        assertEquals(17179869184d, node.getMemoryCapacity(), "absolute memory capacity in bytes");
    }

    @Test
    void nodeMetricsResolveAksAgentPoolLabelAndPodCount() {
        GkeDashboardDto.GkeNodeMetricsDto metrics = mapper.buildNodeMetrics(sampleCluster());

        GkeDashboardDto.GkeNodeInfoDto node = nodeNamed(metrics, "aks-systempool-000000");
        assertEquals("systempool", node.getNodePool());
        assertEquals("Standard_D4s_v3", node.getInstanceType());
        assertEquals(2, node.getPodCount(), "two sample pods are scheduled here");
        assertEquals(500d, node.getCpuUsageMillicores());
    }

    // ── Resource utilization ─────────────────────────────────────────────────

    @Test
    void resourceUtilizationComputesRequestsAgainstCapacity() {
        GkeDashboardDto.GkeResourceUtilizationDto util = mapper.buildResourceUtilization(sampleCluster());

        GkeDashboardDto.GkeNodeResourceDto node = util.getNodes().stream()
                .filter(n -> "aks-systempool-000000".equals(n.getNodeName()))
                .findFirst().orElseThrow();

        // falco-bvd89 requests 150m, prometheus-server requests 250m, capacity 4000m
        assertEquals(10.0d, node.getCpuRequestsPercent(), 0.05);
        assertEquals(4000d, node.getCpuCapacityMillicores());
    }

    // ── Container metrics ────────────────────────────────────────────────────

    @Test
    void containerMetricsFlattenEveryPodsContainers() {
        GkeDashboardDto.GkeContainerMetricsDto metrics = mapper.buildContainerMetrics(sampleCluster());

        // falco-bvd89 has 2, the other three pods have 1 each
        assertEquals(5, metrics.getContainers().size());

        GkeDashboardDto.GkeContainerDto falco = metrics.getContainers().stream()
                .filter(c -> "falco".equals(c.getName()))
                .findFirst().orElseThrow();
        assertEquals("falco-bvd89", falco.getPod());
        assertEquals("falco", falco.getNamespace());
        assertTrue(falco.isReady());
        assertEquals(2, falco.getRestartCount());
        assertEquals(30d, falco.getCpuUsage());
    }

    @Test
    void containerMemoryUsageIsPopulatedInBytes() {
        // Previously left unset, which rendered as 0.0 MiB for every container.
        GkeDashboardDto.GkeContainerMetricsDto metrics = mapper.buildContainerMetrics(sampleCluster());

        GkeDashboardDto.GkeContainerDto falco = metrics.getContainers().stream()
                .filter(c -> "falco".equals(c.getName()) && "falco-bvd89".equals(c.getPod()))
                .findFirst().orElseThrow();

        assertEquals(134217728d, falco.getMemoryUsage(), "128Mi in bytes");
    }

    @Test
    void containersWithoutMetricsReportZeroRatherThanFailing() {
        GkeDashboardDto.GkeContainerMetricsDto metrics = mapper.buildContainerMetrics(sampleCluster());

        // Only falco-bvd89 has a podMetrics entry in the fixture.
        GkeDashboardDto.GkeContainerDto unmeasured = metrics.getContainers().stream()
                .filter(c -> "prometheus-server".equals(c.getName()))
                .findFirst().orElseThrow();

        assertEquals(0d, unmeasured.getCpuUsage());
        assertEquals(0d, unmeasured.getMemoryUsage());
    }

    @Test
    void emptyClusterProducesEmptySectionsRatherThanNulls() {
        AgentDataTransformerService.K8sResourcesResult empty =
                new AgentDataTransformerService.K8sResourcesResult();

        assertTrue(mapper.buildWorkloadHealth(empty).getPods().isEmpty());
        assertTrue(mapper.buildNodeMetrics(empty).getNodes().isEmpty());
        assertTrue(mapper.buildContainerMetrics(empty).getContainers().isEmpty());
        assertTrue(mapper.buildResourceUtilization(empty).getNodes().isEmpty());
    }

    // ── Security metrics ─────────────────────────────────────────────────────

    @Test
    void trivyRunningIsTrueWhenReportsExistEvenWithZeroVulnerabilities() {
        // A clean cluster still runs Trivy. Keying "running" off findings would render
        // "Trivy Not Installed" on a cluster with nothing to fix.
        TrivyDashboard clean = new TrivyDashboard();
        TrivyDashboard.Summary summary = new TrivyDashboard.Summary();
        summary.setTotalVulnerabilityReports(19);
        summary.setUniqueImagesScanned(19);
        clean.setSummary(summary);

        GkeDashboardDto.GkeSecurityMetricsDto dto = mapper.buildSecurityMetrics(clean, null, null);

        assertTrue(dto.isTrivyRunning());
        assertEquals(0d, dto.getTrivyTotalVulnerabilities());
        assertTrue(dto.getTrivyFindings().isEmpty());
    }

    @Test
    void trivySeverityTotalsAndPerImageFindingsAreMapped() {
        GkeDashboardDto.GkeSecurityMetricsDto dto =
                mapper.buildSecurityMetrics(sampleTrivy(), null, null);

        assertTrue(dto.isTrivyRunning());
        assertEquals(46d, dto.getTrivyTotalVulnerabilities(), "3 + 12 + 25 + 6");
        assertEquals(3, dto.getTrivyBySeverity().get("Critical"));
        assertEquals(12, dto.getTrivyBySeverity().get("High"));
        assertNull(dto.getTrivyBySeverity().get("Unknown"), "absent severities are omitted");

        // falco workload has Critical=1 and High=4 → two rows, one per severity
        List<GkeDashboardDto.GkeTrivyFindingDto> falcoRows = dto.getTrivyFindings().stream()
                .filter(f -> "falcosecurity/falco:0.44.1".equals(f.getImage()))
                .toList();
        assertEquals(2, falcoRows.size());
        assertEquals(1, falcoRows.stream()
                .filter(f -> "Critical".equals(f.getSeverity())).findFirst().orElseThrow().getCount());
    }

    @Test
    void kubescapeRunningIsTrueOnAFullyCompliantCluster() {
        KubescapeDashboard compliant = new KubescapeDashboard();
        compliant.setOverallComplianceScore(100d);
        compliant.setTotalWorkloads(31);

        GkeDashboardDto.GkeSecurityMetricsDto dto = mapper.buildSecurityMetrics(null, compliant, null);

        assertTrue(dto.isKubescapeRunning());
        assertEquals(0, dto.getKubescapeTotalFailed());
        assertEquals(31, dto.getKubescapeTotalPassed());
    }

    @Test
    void kubescapeFindingsUseDominantSeverityAndSumFailedResources() {
        GkeDashboardDto.GkeSecurityMetricsDto dto =
                mapper.buildSecurityMetrics(null, sampleKubescape(), null);

        assertTrue(dto.isKubescapeRunning());
        assertEquals(18, dto.getKubescapeTotalFailed(), "2 + 5 + 8 + 3");
        assertEquals(2, dto.getKubescapeFailsBySeverity().get("Critical"));

        GkeDashboardDto.GkeKubescapeFindingDto worst = dto.getKubescapeFindings().stream()
                .filter(f -> "falco".equals(f.getControlName()))
                .findFirst().orElseThrow();
        assertEquals("Critical", worst.getSeverity(), "critical outranks the rest");
        assertEquals(6, worst.getFailedResources(), "1 + 2 + 3 + 0");
    }

    @Test
    void falcoRunningIsFalseWhenTheAgentCollectedNoEvents() {
        // Reflects the agent bug where it queried falco_events_total instead of
        // falcosecurity_falcosidekick_falco_events_total: batch present, events empty.
        AgentDataTransformerService.PrometheusMetricsResult prom =
                new AgentDataTransformerService.PrometheusMetricsResult();

        GkeDashboardDto.GkeSecurityMetricsDto dto = mapper.buildSecurityMetrics(null, null, prom);

        assertFalse(dto.isFalcoRunning());
        assertEquals(0d, dto.getTotalEvents());
        assertTrue(dto.getAlerts().isEmpty());
    }

    @Test
    void falcoEventsAggregateByPriority() {
        AgentDataTransformerService.PrometheusMetricsResult prom =
                new AgentDataTransformerService.PrometheusMetricsResult();
        prom.setFalcoEvents(List.of(
                falcoEvent("Terminal shell in container", "Notice", "falco", 4),
                falcoEvent("Read sensitive file untrusted", "Warning", "kube-system", 7),
                falcoEvent("Write below etc", "Warning", "default", 2)));

        GkeDashboardDto.GkeSecurityMetricsDto dto = mapper.buildSecurityMetrics(null, null, prom);

        assertTrue(dto.isFalcoRunning());
        assertEquals(13d, dto.getTotalEvents());
        assertEquals(9, dto.getEventsByPriority().get("Warning"), "7 + 2 merged");
        assertEquals(4, dto.getEventsByPriority().get("Notice"));
        assertEquals(3, dto.getAlerts().size());
    }

    @Test
    void securityMetricsSectionIsNeverNullWhenAllSourcesAreMissing() {
        // The whole section being null is what made the UI claim all three tools were
        // not installed. An empty-but-present section reports each flag honestly.
        GkeDashboardDto.GkeSecurityMetricsDto dto = mapper.buildSecurityMetrics(null, null, null);

        assertNotNull(dto);
        assertFalse(dto.isFalcoRunning());
        assertFalse(dto.isTrivyRunning());
        assertFalse(dto.isKubescapeRunning());
        assertNotNull(dto.getTrivyBySeverity());
        assertNotNull(dto.getKubescapeFindings());
        assertNotNull(dto.getAlerts());
    }

    @Test
    void oneToolInstalledDoesNotMarkTheOthersAsRunning() {
        GkeDashboardDto.GkeSecurityMetricsDto dto =
                mapper.buildSecurityMetrics(sampleTrivy(), null, null);

        assertTrue(dto.isTrivyRunning());
        assertFalse(dto.isKubescapeRunning());
        assertFalse(dto.isFalcoRunning());
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private Map<String, Object> falcoEvent(String rule, String priority, String namespace, int count) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("rule", rule);
        e.put("priority", priority);
        e.put("namespace", namespace);
        e.put("count", count);
        e.put("collectedAt", "2026-08-07T11:46:52Z");
        return e;
    }

    private TrivyDashboard sampleTrivy() {
        TrivyDashboard trivy = new TrivyDashboard();
        TrivyDashboard.Summary summary = new TrivyDashboard.Summary();
        summary.setTotalCritical(3);
        summary.setTotalHigh(12);
        summary.setTotalMedium(25);
        summary.setTotalLow(6);
        summary.setTotalVulnerabilityReports(19);
        summary.setUniqueImagesScanned(19);
        trivy.setSummary(summary);

        TrivyDashboard.WorkloadVulnerability falco = new TrivyDashboard.WorkloadVulnerability();
        falco.setName("daemonset-falco-falco");
        falco.setNamespace("falco");
        falco.setImage("falcosecurity/falco");
        falco.setTag("0.44.1");
        falco.setCritical(1);
        falco.setHigh(4);

        TrivyDashboard.WorkloadVulnerability coredns = new TrivyDashboard.WorkloadVulnerability();
        coredns.setName("replicaset-coredns-coredns");
        coredns.setNamespace("kube-system");
        coredns.setImage("oss/v2/kubernetes/coredns:v1.13.1-20");
        coredns.setMedium(9);

        trivy.setWorkloads(List.of(falco, coredns));
        return trivy;
    }

    private KubescapeDashboard sampleKubescape() {
        KubescapeDashboard ks = new KubescapeDashboard();
        ks.setOverallComplianceScore(72d);
        ks.setTotalCritical(2);
        ks.setTotalHigh(5);
        ks.setTotalMedium(8);
        ks.setTotalLow(3);
        ks.setTotalWorkloads(31);

        KubescapeDashboard.VulnerabilitySummary falco = new KubescapeDashboard.VulnerabilitySummary();
        falco.setWorkloadName("falco");
        falco.setNamespace("falco");
        falco.setWorkloadKind("DaemonSet");
        falco.setCriticalCount(1);
        falco.setHighCount(2);
        falco.setMediumCount(3);
        falco.setLowCount(0);

        KubescapeDashboard.VulnerabilitySummary clean = new KubescapeDashboard.VulnerabilitySummary();
        clean.setWorkloadName("coredns");
        clean.setNamespace("kube-system");
        clean.setCriticalCount(0);
        clean.setHighCount(0);
        clean.setMediumCount(0);
        clean.setLowCount(0);

        ks.setVulnerabilities(List.of(falco, clean));
        return ks;
    }

    private GkeDashboardDto.GkeNodeInfoDto nodeNamed(GkeDashboardDto.GkeNodeMetricsDto m, String name) {
        return m.getNodes().stream()
                .filter(n -> name.equals(n.getName()))
                .findFirst().orElseThrow(() -> new AssertionError("node not mapped: " + name));
    }

    private AgentDataTransformerService.K8sResourcesResult sampleCluster() {
        AgentDataTransformerService.K8sResourcesResult k8s =
                new AgentDataTransformerService.K8sResourcesResult();
        k8s.setTotalNodes(3);
        k8s.setTotalPods(4);
        k8s.setRunningPods(4);
        k8s.setTotalNamespaces(2);
        k8s.setK8sVersion("1.35.6");

        k8s.setNodes(List.of(
                node("aks-systempool-000000", "Standard_D4s_v3", "systempool", "4", "16Gi", true, false),
                node("aks-systempool-000001", "Standard_D4s_v3", "systempool", "4", "16Gi", true, false),
                node("aks-userpool-000000", "Standard_D2s_v3", "userpool", "2", "8Gi", false, true)));

        k8s.setNodeMetrics(List.of(
                nodeMetric("aks-systempool-000000", "500m", "2Gi", 12.5d, 12.5d),
                nodeMetric("aks-systempool-000001", "800m", "3Gi", 20.0d, 18.75d),
                nodeMetric("aks-userpool-000000", "200m", "1Gi", 10.0d, 12.5d)));

        k8s.setPods(List.of(
                pod("falco-bvd89", "falco", "aks-systempool-000000", "Running", "DaemonSet", List.of(
                        container("falco", "falcosecurity/falco:0.44.1", true, 2, "100m", "512Mi"),
                        container("falcoctl", "falcosecurity/falcoctl:0.13.0", true, 3, "50m", "128Mi"))),
                pod("prometheus-server-5cf694d57c-cxjk7", "monitoring", "aks-systempool-000000", "Running",
                        "ReplicaSet", List.of(
                                container("prometheus-server", "prometheus/prometheus:v3", true, 0, "250m", "1Gi"))),
                pod("falco-j6bvr", "falco", "aks-systempool-000001", "Running", "DaemonSet", List.of(
                        container("falco", "falcosecurity/falco:0.44.1", true, 0, "100m", "512Mi"))),
                pod("falco-p5mzs", "falco", "aks-userpool-000000", "Running", "DaemonSet", List.of(
                        container("falco", "falcosecurity/falco:0.44.1", true, 0, "100m", "512Mi")))));

        k8s.setPodMetrics(List.of(
                podMetric("falco-bvd89", "falco", Map.of("falco", "30m", "falcoctl", "15m"))));

        k8s.setDeployments(List.of(deployment("prometheus-server", "monitoring", 1, 1, 1)));
        k8s.setStatefulSets(new ArrayList<>());
        k8s.setDaemonSets(List.of(daemonSet("falco", "falco", 3, 3)));
        return k8s;
    }

    private Map<String, Object> node(String name, String instanceType, String pool,
                                     String cpu, String memory, boolean ready, boolean diskPressure) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("name", name);
        n.put("instanceType", instanceType);
        n.put("zone", "eastus-1");
        n.put("internalIp", "10.224.0.4");
        n.put("kubeletVersion", "v1.35.6");
        n.put("capacityCpu", cpu);
        n.put("capacityMemory", memory);
        n.put("labels", Map.of("agentpool", pool));
        List<Map<String, Object>> conditions = new ArrayList<>();
        conditions.add(condition("Ready", ready ? "True" : "False"));
        conditions.add(condition("DiskPressure", diskPressure ? "True" : "False"));
        conditions.add(condition("MemoryPressure", "False"));
        n.put("conditions", conditions);
        return n;
    }

    private Map<String, Object> condition(String type, String status) {
        Map<String, Object> c = new HashMap<>();
        c.put("type", type);
        c.put("status", status);
        return c;
    }

    private Map<String, Object> nodeMetric(String nodeName, String cpu, String memory,
                                           double cpuPct, double memPct) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nodeName", nodeName);
        m.put("cpuUsage", cpu);
        m.put("memoryUsage", memory);
        m.put("cpuPercent", cpuPct);
        m.put("memoryPercent", memPct);
        return m;
    }

    private Map<String, Object> pod(String name, String namespace, String nodeName, String phase,
                                    String controllerKind, List<Map<String, Object>> containers) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("name", name);
        p.put("namespace", namespace);
        p.put("nodeName", nodeName);
        p.put("phase", phase);
        p.put("controllerKind", controllerKind);
        p.put("controllerName", name);
        p.put("podIp", "10.224.0.99");
        p.put("age", "2026-08-06T08:38:03Z");
        p.put("containers", containers);
        return p;
    }

    private Map<String, Object> container(String name, String image, boolean ready,
                                          int restarts, String cpuRequest, String memRequest) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("name", name);
        c.put("image", image);
        c.put("ready", ready);
        c.put("restartCount", restarts);
        c.put("state", "running");
        c.put("cpuRequest", cpuRequest);
        c.put("memoryRequest", memRequest);
        return c;
    }

    private Map<String, Object> podMetric(String podName, String namespace, Map<String, String> cpuByContainer) {
        Map<String, Object> pm = new LinkedHashMap<>();
        pm.put("podName", podName);
        pm.put("namespace", namespace);
        List<Map<String, Object>> containers = new ArrayList<>();
        cpuByContainer.forEach((name, cpu) -> {
            Map<String, Object> cu = new LinkedHashMap<>();
            cu.put("name", name);
            cu.put("cpuUsage", cpu);
            cu.put("memoryUsage", "128Mi");
            containers.add(cu);
        });
        pm.put("containers", containers);
        return pm;
    }

    private Map<String, Object> deployment(String name, String namespace, int replicas, int ready, int available) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("name", name);
        d.put("namespace", namespace);
        d.put("replicas", replicas);
        d.put("readyReplicas", ready);
        d.put("availableReplicas", available);
        return d;
    }

    private Map<String, Object> daemonSet(String name, String namespace, int desired, int ready) {
        Map<String, Object> ds = new LinkedHashMap<>();
        ds.put("name", name);
        ds.put("namespace", namespace);
        ds.put("desiredNumberScheduled", desired);
        ds.put("numberReady", ready);
        return ds;
    }
}
