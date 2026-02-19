package com.xammer.cloud.dto.gcp;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Complete GKE cluster dashboard DTO.
 * Mirrors the EKS dashboard structure but adapted for GKE.
 * Overview + Node Pools always populated (from ClusterManagerClient).
 * WorkloadHealth populated only when cluster is connected (has Prometheus URL).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GkeDashboardDto {

    private String clusterName;
    private String location;
    private String status;
    private String version;
    private String endpoint;
    private boolean connected;
    private LocalDateTime lastSyncedAt;

    /** Cluster-level resource metrics (from Prometheus, null if disconnected) */
    private GkeOverviewDto overview;

    /** Node pools (from ClusterManagerClient, always available) */
    private List<GkeNodePoolDto> nodePools;

    /** Workload and pod data (from Prometheus, null if disconnected) */
    private GkeWorkloadHealthDto workloadHealth;

    /** Cost metrics estimated from Prometheus resource usage (null if disconnected) */
    private GkeCostMetricsDto costMetrics;

    /** Per-node resource utilization (null if disconnected) */
    private GkeResourceUtilizationDto resourceUtilization;

    /** Per-node status and conditions (null if disconnected) */
    private GkeNodeMetricsDto nodeMetrics;

    /** Container-level metrics derived from pod data (null if disconnected) */
    private GkeContainerMetricsDto containerMetrics;

    /** Falco runtime security events (null if disconnected, empty if Falco not installed) */
    private GkeSecurityMetricsDto securityMetrics;

    // --- Nested DTOs ---

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GkeOverviewDto {
        private int totalNodes;
        private int totalPods;
        private int totalNamespaces;
        private double cpuUsageMillicores;
        private double cpuCapacityMillicores;
        private double cpuUsagePercent;
        private double memoryUsageBytes;
        private double memoryCapacityBytes;
        private double memoryUsagePercent;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GkeNodePoolDto {
        private String name;
        private String machineType;
        private int nodeCount;
        private String version;
        private boolean autoscalingEnabled;
        private int minNodes;
        private int maxNodes;
        private String status;
        private String diskSizeGb;
        private String imageType;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GkeWorkloadHealthDto {
        private List<GkePodDto> pods;
        private List<GkeDeploymentDto> deployments;
        private List<GkeWorkloadDto> statefulsets;
        private List<GkeWorkloadDto> daemonsets;
        private double clusterHealthScore;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GkePodDto {
        private String name;
        private String namespace;
        private String phase;
        private String nodeName;
        private int restartCount;
        private double cpuUsage;
        private double memoryUsage;
        private double cpuRequest;
        private double memoryRequest;
        private String age;
        private String podIp;
        private String ownerKind;
        private String ownerName;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GkeDeploymentDto {
        private String name;
        private String namespace;
        private int replicas;
        private int availableReplicas;
        private int readyReplicas;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GkeWorkloadDto {
        private String name;
        private String namespace;
        private int replicas;
        private int readyReplicas;
        private String kind;
    }

    // ---- Cost Metrics ----

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class GkeCostMetricsDto {
        private double dailyCost;
        private List<GkeNamespaceCostDto> namespaceCosts;
        private List<GkeWorkloadCostDto> workloadCosts;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class GkeNamespaceCostDto {
        private String namespace;
        private double cpuUsageMillicores;
        private double memoryUsageMb;
        private int podCount;
        private double dailyCost;
        private double monthlyCost;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class GkeWorkloadCostDto {
        private String name;
        private String namespace;
        private String kind;
        private double cpuUsageMillicores;
        private double memoryUsageMb;
        private int podCount;
        private double dailyCost;
        private double monthlyCost;
    }

    // ---- Resource Utilization (per-node) ----

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class GkeResourceUtilizationDto {
        private List<GkeNodeResourceDto> nodes;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class GkeNodeResourceDto {
        private String nodeName;
        private double cpuUsagePercent;
        private double memoryUsagePercent;
        private double cpuUsageMillicores;
        private double cpuCapacityMillicores;
        private double cpuRequestsPercent;
        private double memoryRequestsPercent;
    }

    // ---- Node Metrics (conditions + info) ----

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class GkeNodeMetricsDto {
        private List<GkeNodeInfoDto> nodes;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class GkeNodeInfoDto {
        private String name;
        private boolean ready;
        private boolean diskPressure;
        private boolean memoryPressure;
        private String instanceType;
        private String zone;
        private String internalIp;
        private String kubeletVersion;
        private String nodePool;
        private double cpuCapacity;
        private double memoryCapacity;
        private double cpuUsage;
        private double cpuUsageMillicores;
        private double memoryUsage;
        private int podCount;
    }

    // ---- Container Metrics ----

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class GkeContainerMetricsDto {
        private List<GkeContainerDto> containers;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class GkeContainerDto {
        private String name;
        private String pod;
        private String namespace;
        private String image;
        private String state;
        private boolean ready;
        private int restartCount;
        private double cpuUsage;
        private double memoryUsage;
        private double fsUsage;
    }

    // ---- Security Metrics (Falco + Kubescape + Trivy) ----

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class GkeSecurityMetricsDto {
        private double totalEvents;
        private Map<String, Integer> eventsByPriority;
        private List<GkeSecurityAlertDto> alerts;
        private boolean falcoRunning;  // true if Falco pods exist in cluster

        // Kubescape
        private boolean kubescapeRunning;
        private int kubescapeTotalFailed;
        private int kubescapeTotalPassed;
        private Map<String, Integer> kubescapeFailsBySeverity;
        private List<GkeKubescapeFindingDto> kubescapeFindings;

        // Trivy
        private boolean trivyRunning;
        private double trivyTotalVulnerabilities;
        private Map<String, Integer> trivyBySeverity;
        private List<GkeTrivyFindingDto> trivyFindings;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class GkeSecurityAlertDto {
        private String rule;
        private String priority;
        private int count;
        private String lastSeen;
        private String namespace;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class GkeKubescapeFindingDto {
        private String controlName;
        private String severity;
        private int failedResources;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class GkeTrivyFindingDto {
        private String image;
        private String namespace;
        private String severity;
        private int count;
    }
}
