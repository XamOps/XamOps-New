package com.xammer.cloud.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.AgentDataCache;
import com.xammer.cloud.dto.k8s.*;
import com.xammer.cloud.repository.AgentDataCacheRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.when;

/**
 * Unit tests for AgentDataTransformerService.
 *
 * Tests JSON parsing in both K8s CRD format and agent simplified format,
 * summary aggregation, empty-cache handling, and monthly cost calculation.
 * AgentDataCacheRepository is mocked — no database needed.
 */
class AgentDataTransformerServiceTest {

    @Mock
    private AgentDataCacheRepository repo;

    private AgentDataTransformerService service;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new AgentDataTransformerService(repo);
    }

    // ── Trivy: empty cache ────────────────────────────────────────────────────

    @Test
    void trivy_emptyCache_returnsEmptyDashboard() {
        when(repo.findByClusterConfigIdAndDataType(1L, "trivy-vulnerabilities"))
                .thenReturn(Optional.empty());

        TrivyDashboard result = service.transformTrivyData(1L);

        assertThat(result.getWorkloads()).isNotNull().isEmpty();
        assertThat(result.getSummary()).isNotNull();
        assertThat(result.getSummary().getTotalCritical()).isEqualTo(0);
    }

    // ── Trivy: agent simplified format ───────────────────────────────────────

    @Test
    void trivy_agentFormat_parsesWorkloads() throws Exception {
        String json = mapper.writeValueAsString(List.of(
                Map.of(
                        "name", "nginx-deploy",
                        "namespace", "production",
                        "kind", "Deployment",
                        "image", "nginx",
                        "tag", "1.24",
                        "summary", Map.of("criticalCount", 2, "highCount", 3, "mediumCount", 5, "lowCount", 10),
                        "vulnerabilities", List.of()
                )
        ));

        when(repo.findByClusterConfigIdAndDataType(1L, "trivy-vulnerabilities"))
                .thenReturn(Optional.of(cache("trivy-vulnerabilities", json)));

        TrivyDashboard result = service.transformTrivyData(1L);

        assertThat(result.getWorkloads()).hasSize(1);
        TrivyDashboard.WorkloadVulnerability w = result.getWorkloads().get(0);
        assertThat(w.getName()).isEqualTo("nginx-deploy");
        assertThat(w.getNamespace()).isEqualTo("production");
        assertThat(w.getCritical()).isEqualTo(2);
        assertThat(w.getHigh()).isEqualTo(3);
    }

    // ── Trivy: K8s CRD format ─────────────────────────────────────────────────

    @Test
    void trivy_crdFormat_parsesMetadataAndReport() throws Exception {
        String json = mapper.writeValueAsString(List.of(
                Map.of(
                        "metadata", Map.of(
                                "name", "crd-workload",
                                "namespace", "default",
                                "labels", Map.of(
                                        "trivy-operator.resource.kind", "DaemonSet",
                                        "trivy-operator.container.name", "fluent-bit"
                                )
                        ),
                        "report", Map.of(
                                "summary", Map.of("criticalCount", 1, "highCount", 0, "mediumCount", 2, "lowCount", 4),
                                "vulnerabilities", List.of()
                        )
                )
        ));

        when(repo.findByClusterConfigIdAndDataType(2L, "trivy-vulnerabilities"))
                .thenReturn(Optional.of(cache("trivy-vulnerabilities", json)));

        TrivyDashboard result = service.transformTrivyData(2L);

        assertThat(result.getWorkloads()).hasSize(1);
        TrivyDashboard.WorkloadVulnerability w = result.getWorkloads().get(0);
        assertThat(w.getName()).isEqualTo("crd-workload");
        assertThat(w.getKind()).isEqualTo("DaemonSet");
        assertThat(w.getImage()).isEqualTo("fluent-bit");
        assertThat(w.getCritical()).isEqualTo(1);
    }

    // ── Trivy: summary aggregation ────────────────────────────────────────────

    @Test
    void trivy_multiplWorkloads_summaryAggregatesCorrectly() throws Exception {
        String json = mapper.writeValueAsString(List.of(
                Map.of("name", "a", "namespace", "ns1",
                        "summary", Map.of("criticalCount", 2, "highCount", 1, "mediumCount", 0, "lowCount", 0),
                        "vulnerabilities", List.of()),
                Map.of("name", "b", "namespace", "ns2",
                        "summary", Map.of("criticalCount", 1, "highCount", 3, "mediumCount", 2, "lowCount", 5),
                        "vulnerabilities", List.of())
        ));

        when(repo.findByClusterConfigIdAndDataType(1L, "trivy-vulnerabilities"))
                .thenReturn(Optional.of(cache("trivy-vulnerabilities", json)));

        TrivyDashboard result = service.transformTrivyData(1L);

        assertThat(result.getSummary().getTotalCritical()).isEqualTo(3);
        assertThat(result.getSummary().getTotalHigh()).isEqualTo(4);
        assertThat(result.getSummary().getTotalVulnerabilityReports()).isEqualTo(2);
        assertThat(result.getSummary().getNamespacesCovered()).isEqualTo(2);
    }

    // ── Trivy: vulnerability details ──────────────────────────────────────────

    @Test
    void trivy_vulnerabilityDetails_parsedCorrectly() throws Exception {
        String json = mapper.writeValueAsString(List.of(
                Map.of("name", "w1", "namespace", "default",
                        "summary", Map.of("criticalCount", 1, "highCount", 0, "mediumCount", 0, "lowCount", 0),
                        "vulnerabilities", List.of(
                                Map.of("vulnerabilityID", "CVE-2024-1234", "severity", "CRITICAL",
                                        "resource", "openssl", "installedVersion", "3.0.1",
                                        "fixedVersion", "3.0.2", "score", 9.8)
                        ))
        ));

        when(repo.findByClusterConfigIdAndDataType(1L, "trivy-vulnerabilities"))
                .thenReturn(Optional.of(cache("trivy-vulnerabilities", json)));

        TrivyDashboard result = service.transformTrivyData(1L);
        TrivyDashboard.VulnerabilityDetail detail = result.getWorkloads().get(0).getVulnerabilities().get(0);

        assertThat(detail.getVulnerabilityId()).isEqualTo("CVE-2024-1234");
        assertThat(detail.getSeverity()).isEqualTo("CRITICAL");
        assertThat(detail.getPackageName()).isEqualTo("openssl");
        assertThat(detail.getScore()).isEqualTo(9.8);
    }

    // ── Kubescape: empty cache ────────────────────────────────────────────────

    @Test
    void kubescape_emptyCache_returnsEmptyDashboard() {
        when(repo.findByClusterConfigIdAndDataType(1L, "kubescape-compliance"))
                .thenReturn(Optional.empty());

        KubescapeDashboard result = service.transformKubescapeData(1L);

        assertThat(result).isNotNull();
        assertThat(result.getTotalCritical()).isNull();
    }

    // ── Kubescape: agent format ───────────────────────────────────────────────

    @Test
    void kubescape_agentFormat_parsesScansAndAggregates() throws Exception {
        String json = mapper.writeValueAsString(List.of(
                Map.of(
                        "namespace", "kube-system",
                        "name", "scan-1",
                        "severities", Map.of("critical", 0, "high", 2, "medium", 5, "low", 8),
                        "controls", Map.of("passed", 40, "failed", 10)
                ),
                Map.of(
                        "namespace", "default",
                        "name", "scan-2",
                        "severities", Map.of("critical", 1, "high", 0, "medium", 3, "low", 2),
                        "controls", Map.of("passed", 20, "failed", 5)
                )
        ));

        when(repo.findByClusterConfigIdAndDataType(1L, "kubescape-compliance"))
                .thenReturn(Optional.of(cache("kubescape-compliance", json)));

        KubescapeDashboard result = service.transformKubescapeData(1L);

        assertThat(result.getTotalCritical()).isEqualTo(1);
        assertThat(result.getTotalHigh()).isEqualTo(2);
        assertThat(result.getConfigScans()).hasSize(2);
    }

    // ── Kubescape: compliance score ───────────────────────────────────────────

    @Test
    void kubescape_complianceScore_calculatedCorrectly() throws Exception {
        String json = mapper.writeValueAsString(List.of(
                Map.of("namespace", "default", "name", "scan",
                        "severities", Map.of("critical", 0, "high", 0, "medium", 0, "low", 0),
                        "controls", Map.of("passed", 80, "failed", 20))  // 80% pass rate
        ));

        when(repo.findByClusterConfigIdAndDataType(1L, "kubescape-compliance"))
                .thenReturn(Optional.of(cache("kubescape-compliance", json)));

        KubescapeDashboard result = service.transformKubescapeData(1L);

        assertThat(result.getOverallComplianceScore()).isCloseTo(80.0, within(0.01));
    }

    @Test
    void kubescape_complianceScore_between0And100() throws Exception {
        String json = mapper.writeValueAsString(List.of(
                Map.of("namespace", "ns", "name", "s",
                        "severities", Map.of("critical", 5, "high", 5, "medium", 5, "low", 5),
                        "controls", Map.of("passed", 60, "failed", 40))
        ));

        when(repo.findByClusterConfigIdAndDataType(1L, "kubescape-compliance"))
                .thenReturn(Optional.of(cache("kubescape-compliance", json)));

        KubescapeDashboard result = service.transformKubescapeData(1L);

        assertThat(result.getOverallComplianceScore()).isBetween(0.0, 100.0);
    }

    // ── OpenCost: empty cache ─────────────────────────────────────────────────

    @Test
    void openCost_emptyCache_returnsDisabledDashboard() {
        when(repo.findByClusterConfigIdAndDataType(1L, "opencost-cost"))
                .thenReturn(Optional.empty());

        OpenCostDashboard result = service.transformOpenCostData(1L);

        assertThat(result.isEnabled()).isFalse();
        assertThat(result.getNamespaceAllocations()).isNotNull().isEmpty();
    }

    // ── OpenCost: data present ────────────────────────────────────────────────

    @Test
    void openCost_withData_isEnabledAndMonthlyCostCalculated() throws Exception {
        String json = mapper.writeValueAsString(List.of(
                Map.of("namespace", "default", "totalCost", 10.0, "cpuCost", 5.0, "ramCost", 3.0, "pvCost", 1.0, "networkCost", 1.0),
                Map.of("namespace", "kube-system", "totalCost", 5.0, "cpuCost", 2.0, "ramCost", 2.0, "pvCost", 0.5, "networkCost", 0.5)
        ));

        when(repo.findByClusterConfigIdAndDataType(1L, "opencost-cost"))
                .thenReturn(Optional.of(cache("opencost-cost", json)));

        OpenCostDashboard result = service.transformOpenCostData(1L);

        assertThat(result.isEnabled()).isTrue();
        assertThat(result.getNamespaceAllocations()).hasSize(2);
        assertThat(result.getSummary().getTotalDailyCost()).isEqualTo(15.0);
        assertThat(result.getSummary().getTotalMonthlyCost()).isEqualTo(15.0 * 30);
    }

    @Test
    void openCost_monthlyEquals30xDaily() throws Exception {
        String json = mapper.writeValueAsString(List.of(
                Map.of("namespace", "ns1", "totalCost", 7.0, "cpuCost", 3.0, "ramCost", 2.0, "pvCost", 1.0, "networkCost", 1.0)
        ));

        when(repo.findByClusterConfigIdAndDataType(1L, "opencost-cost"))
                .thenReturn(Optional.of(cache("opencost-cost", json)));

        OpenCostDashboard result = service.transformOpenCostData(1L);

        assertThat(result.getSummary().getTotalMonthlyCost())
                .isCloseTo(result.getSummary().getTotalDailyCost() * 30, within(0.001));
    }

    @Test
    void openCost_itemsWithoutNamespaceAreSkipped() throws Exception {
        String json = mapper.writeValueAsString(List.of(
                Map.of("totalCost", 5.0),  // no namespace key
                Map.of("namespace", "valid-ns", "totalCost", 3.0, "cpuCost", 1.0, "ramCost", 1.0, "pvCost", 0.5, "networkCost", 0.5)
        ));

        when(repo.findByClusterConfigIdAndDataType(1L, "opencost-cost"))
                .thenReturn(Optional.of(cache("opencost-cost", json)));

        OpenCostDashboard result = service.transformOpenCostData(1L);

        assertThat(result.getNamespaceAllocations()).hasSize(1);
        assertThat(result.getNamespaceAllocations().get(0).getNamespace()).isEqualTo("valid-ns");
    }

    // ── Karpenter: no cache ───────────────────────────────────────────────────

    @Test
    void karpenter_emptyCache_notInstalled() {
        when(repo.findByClusterConfigIdAndDataType(1L, "karpenter-data"))
                .thenReturn(Optional.empty());

        KarpenterDashboard result = service.transformKarpenterData(1L);

        assertThat(result.isInstalled()).isFalse();
    }

    // ── Karpenter: with data ──────────────────────────────────────────────────

    @Test
    void karpenter_withData_installedAndVersionSet() throws Exception {
        String json = mapper.writeValueAsString(Map.of(
                "version", "v0.35.2",
                "healthy", true,
                "podStatus", "Running",
                "nodePools", List.of(),
                "ec2NodeClasses", List.of(),
                "nodeClaims", List.of()
        ));

        when(repo.findByClusterConfigIdAndDataType(1L, "karpenter-data"))
                .thenReturn(Optional.of(cache("karpenter-data", json)));

        KarpenterDashboard result = service.transformKarpenterData(1L);

        assertThat(result.isInstalled()).isTrue();
        assertThat(result.getVersion()).isEqualTo("v0.35.2");
        assertThat(result.isHealthy()).isTrue();
        assertThat(result.getPodStatus()).isEqualTo("Running");
    }

    @Test
    void karpenter_nodeClaims_spotAndOnDemandCountedCorrectly() throws Exception {
        String json = mapper.writeValueAsString(Map.of(
                "version", "v0.35.2", "healthy", true, "podStatus", "Running",
                "nodePools", List.of(),
                "ec2NodeClasses", List.of(),
                "nodeClaims", List.of(
                        Map.of("metadata", Map.of("name", "nc-1",
                                "labels", Map.of("karpenter.sh/capacity-type", "spot")),
                                "status", Map.of("nodeName", "node-1", "instanceType", "t3.medium", "zone", "ap-south-1a",
                                        "conditions", List.of(Map.of("type", "Ready", "status", "True")))),
                        Map.of("metadata", Map.of("name", "nc-2",
                                "labels", Map.of("karpenter.sh/capacity-type", "on-demand")),
                                "status", Map.of("nodeName", "node-2", "instanceType", "m5.large", "zone", "ap-south-1b",
                                        "conditions", List.of(Map.of("type", "Ready", "status", "True"))))
                )
        ));

        when(repo.findByClusterConfigIdAndDataType(1L, "karpenter-data"))
                .thenReturn(Optional.of(cache("karpenter-data", json)));

        KarpenterDashboard result = service.transformKarpenterData(1L);

        assertThat(result.getSpotNodesCount()).isEqualTo(1);
        assertThat(result.getOnDemandNodesCount()).isEqualTo(1);
        assertThat(result.getTotalActiveClaims()).isEqualTo(2);
        assertThat(result.getTotalNodeClaims()).isEqualTo(2);
    }

    @Test
    void karpenter_nodePools_parsedCorrectly() throws Exception {
        String json = mapper.writeValueAsString(Map.of(
                "version", "v0.35.2", "healthy", true, "podStatus", "Running",
                "nodeClaims", List.of(),
                "ec2NodeClasses", List.of(),
                "nodePools", List.of(
                        Map.of("metadata", Map.of("name", "default-pool", "creationTimestamp", "2024-01-01T00:00:00Z"),
                                "spec", Map.of(
                                        "limits", Map.of("cpu", "1000", "memory", "2000Gi"),
                                        "disruption", Map.of("consolidationPolicy", "WhenUnderutilized"),
                                        "template", Map.of("spec", Map.of(
                                                "nodeClassRef", Map.of("name", "default"),
                                                "requirements", List.of(
                                                        Map.of("key", "karpenter.sh/capacity-type", "values", List.of("spot", "on-demand"))
                                                )
                                        ))
                                ))
                )
        ));

        when(repo.findByClusterConfigIdAndDataType(1L, "karpenter-data"))
                .thenReturn(Optional.of(cache("karpenter-data", json)));

        KarpenterDashboard result = service.transformKarpenterData(1L);

        assertThat(result.getTotalNodePools()).isEqualTo(1);
        KarpenterDashboard.NodePoolInfo pool = result.getNodePools().get(0);
        assertThat(pool.getName()).isEqualTo("default-pool");
        assertThat(pool.getCpuLimit()).isEqualTo("1000");
        assertThat(pool.getCapacityTypes()).contains("spot", "on-demand");
    }

    // ── Resilience: malformed JSON is swallowed gracefully ────────────────────

    @Test
    void trivy_malformedJson_returnsEmptyDashboard() {
        when(repo.findByClusterConfigIdAndDataType(1L, "trivy-vulnerabilities"))
                .thenReturn(Optional.of(cache("trivy-vulnerabilities", "not-valid-json{")));

        TrivyDashboard result = service.transformTrivyData(1L);

        assertThat(result).isNotNull();
        assertThat(result.getWorkloads()).isEmpty();
    }

    @Test
    void openCost_malformedJson_returnsDisabledDashboard() {
        when(repo.findByClusterConfigIdAndDataType(1L, "opencost-cost"))
                .thenReturn(Optional.of(cache("opencost-cost", "{bad}")));

        OpenCostDashboard result = service.transformOpenCostData(1L);

        assertThat(result.isEnabled()).isFalse();
    }

    // ── hasAgentData helper ───────────────────────────────────────────────────

    @Test
    void hasAgentData_returnsTrue_whenCachePresent() {
        when(repo.countDataTypesByCluster(1L)).thenReturn(3);
        assertThat(service.hasAgentData(1L)).isTrue();
    }

    @Test
    void hasAgentData_returnsFalse_whenCacheEmpty() {
        when(repo.countDataTypesByCluster(1L)).thenReturn(0);
        assertThat(service.hasAgentData(1L)).isFalse();
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private AgentDataCache cache(String dataType, String json) {
        AgentDataCache c = new AgentDataCache();
        c.setDataType(dataType);
        c.setDataJson(json);
        c.setClusterConfigId(1L);
        c.setCollectedAt(java.time.LocalDateTime.now());
        return c;
    }
}
