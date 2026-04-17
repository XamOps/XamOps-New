package com.xammer.cloud.dto.autoscaling;

import java.util.List;

/**
 * DTO returned by GET /api/eks/autoscaling/{clusterName}
 *
 * Mirrors the frontend AutoscalerDashboard interface exactly.
 * All sub-sections are populated from:
 *   - agent_data_cache (dataType = "autoscaling-policy", "autoscaling-metrics", "autoscaling-events")
 *   - kubernetes_cluster_config (agentStatus, autoscalingEnabled, agentVersion, lastAgentCheckin)
 *   - agent_events (for the activity feed)
 *
 * When autoscalingEnabled = false on the cluster config, the controller returns a
 * lightweight disabled-state response so the frontend can show the "not enabled" banner.
 */
public class AutoscalerDashboardDto {

    /** True when autoscaling_enabled=true in kubernetes_cluster_config. False = feature not activated. */
    private boolean autoscalingEnabled = false;
    private AgentStatusDto agentStatus;
    private AutoscalingKpisDto kpis;
    private ScaleOutPolicyDto scaleOutPolicy;
    private EvictorPolicyDto evictorPolicy;
    private WorkloadRightsizerPolicyDto workloadRightsizerPolicy;
    private List<NodeTemplateDto> nodeTemplates;
    private RebalancerConfigDto rebalancer;
    private SafetyGuardsDto safetyGuards;
    private NodeOptimizerPolicyDto nodeOptimizerPolicy;
    private List<AutoscalingEventDto> recentEvents;

    // ─── Getters / Setters ────────────────────────────────────────────────────

    public boolean isAutoscalingEnabled() { return autoscalingEnabled; }
    public void setAutoscalingEnabled(boolean autoscalingEnabled) { this.autoscalingEnabled = autoscalingEnabled; }

    public AgentStatusDto getAgentStatus() { return agentStatus; }
    public void setAgentStatus(AgentStatusDto agentStatus) { this.agentStatus = agentStatus; }

    public AutoscalingKpisDto getKpis() { return kpis; }
    public void setKpis(AutoscalingKpisDto kpis) { this.kpis = kpis; }

    public ScaleOutPolicyDto getScaleOutPolicy() { return scaleOutPolicy; }
    public void setScaleOutPolicy(ScaleOutPolicyDto scaleOutPolicy) { this.scaleOutPolicy = scaleOutPolicy; }

    public EvictorPolicyDto getEvictorPolicy() { return evictorPolicy; }
    public void setEvictorPolicy(EvictorPolicyDto evictorPolicy) { this.evictorPolicy = evictorPolicy; }

    public WorkloadRightsizerPolicyDto getWorkloadRightsizerPolicy() { return workloadRightsizerPolicy; }
    public void setWorkloadRightsizerPolicy(WorkloadRightsizerPolicyDto p) { this.workloadRightsizerPolicy = p; }

    public List<NodeTemplateDto> getNodeTemplates() { return nodeTemplates; }
    public void setNodeTemplates(List<NodeTemplateDto> nodeTemplates) { this.nodeTemplates = nodeTemplates; }

    public RebalancerConfigDto getRebalancer() { return rebalancer; }
    public void setRebalancer(RebalancerConfigDto rebalancer) { this.rebalancer = rebalancer; }

    public SafetyGuardsDto getSafetyGuards() { return safetyGuards; }
    public void setSafetyGuards(SafetyGuardsDto safetyGuards) { this.safetyGuards = safetyGuards; }

    public NodeOptimizerPolicyDto getNodeOptimizerPolicy() { return nodeOptimizerPolicy; }
    public void setNodeOptimizerPolicy(NodeOptimizerPolicyDto nodeOptimizerPolicy) { this.nodeOptimizerPolicy = nodeOptimizerPolicy; }

    public List<AutoscalingEventDto> getRecentEvents() { return recentEvents; }
    public void setRecentEvents(List<AutoscalingEventDto> recentEvents) { this.recentEvents = recentEvents; }

    // ─── Nested DTOs ─────────────────────────────────────────────────────────

    public static class AgentStatusDto {
        /** Is the main agent pod healthy (reported by agent heartbeat) */
        private boolean agentHealthy;
        /** Is the cluster-controller component healthy */
        private boolean clusterControllerHealthy;
        /** Is the Evictor component healthy */
        private boolean evictorHealthy;
        /** Is the Workload Autoscaler component healthy */
        private boolean workloadAutoscalerHealthy;
        /** full | scoped | disabled */
        private String mode;
        /** ISO timestamp of last agent sync (from kubernetes_cluster_config.last_agent_checkin) */
        private String lastSyncAt;
        /** Agent version string (from kubernetes_cluster_config.agent_version) */
        private String agentVersion;

        public boolean isAgentHealthy() { return agentHealthy; }
        public void setAgentHealthy(boolean v) { this.agentHealthy = v; }
        public boolean isClusterControllerHealthy() { return clusterControllerHealthy; }
        public void setClusterControllerHealthy(boolean v) { this.clusterControllerHealthy = v; }
        public boolean isEvictorHealthy() { return evictorHealthy; }
        public void setEvictorHealthy(boolean v) { this.evictorHealthy = v; }
        public boolean isWorkloadAutoscalerHealthy() { return workloadAutoscalerHealthy; }
        public void setWorkloadAutoscalerHealthy(boolean v) { this.workloadAutoscalerHealthy = v; }
        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }
        public String getLastSyncAt() { return lastSyncAt; }
        public void setLastSyncAt(String lastSyncAt) { this.lastSyncAt = lastSyncAt; }
        public String getAgentVersion() { return agentVersion; }
        public void setAgentVersion(String agentVersion) { this.agentVersion = agentVersion; }
    }

    public static class AutoscalingKpisDto {
        /** Nodes removed by Evictor today */
        private int nodesSavedToday;
        /** USD saved this week from bin-packing + rightsizing */
        private double costSavedThisWeek;
        /** Pending pods resolved by scale-out today */
        private int pendingPodsResolved;
        /** Currently active rebalancing operations */
        private int activeRebalances;
        /** % of nodes on spot (via XamOps Spot Automation) */
        private int spotRatio;
        /** Average CPU utilization across all managed nodes */
        private int avgNodeUtilizationCpu;
        /** Average memory utilization across all managed nodes */
        private int avgNodeUtilizationMemory;
        /** Total nodes under autoscaler management */
        private int totalManagedNodes;

        public int getNodesSavedToday() { return nodesSavedToday; }
        public void setNodesSavedToday(int v) { this.nodesSavedToday = v; }
        public double getCostSavedThisWeek() { return costSavedThisWeek; }
        public void setCostSavedThisWeek(double v) { this.costSavedThisWeek = v; }
        public int getPendingPodsResolved() { return pendingPodsResolved; }
        public void setPendingPodsResolved(int v) { this.pendingPodsResolved = v; }
        public int getActiveRebalances() { return activeRebalances; }
        public void setActiveRebalances(int v) { this.activeRebalances = v; }
        public int getSpotRatio() { return spotRatio; }
        public void setSpotRatio(int v) { this.spotRatio = v; }
        public int getAvgNodeUtilizationCpu() { return avgNodeUtilizationCpu; }
        public void setAvgNodeUtilizationCpu(int v) { this.avgNodeUtilizationCpu = v; }
        public int getAvgNodeUtilizationMemory() { return avgNodeUtilizationMemory; }
        public void setAvgNodeUtilizationMemory(int v) { this.avgNodeUtilizationMemory = v; }
        public int getTotalManagedNodes() { return totalManagedNodes; }
        public void setTotalManagedNodes(int v) { this.totalManagedNodes = v; }
    }

    public static class ScaleOutPolicyDto {
        private boolean enabled = true;
        /** Spare CPU capacity to keep warm (% of total cluster CPU) */
        private int headroomCpuPercent = 10;
        /** Spare memory capacity to keep warm (% of total cluster memory) */
        private int headroomMemoryPercent = 10;
        /** Minimum CPU cores for any provisioned node */
        private int minCpuCores = 2;
        /** Maximum CPU cores for any provisioned node */
        private int maxCpuCores = 32;
        /** Minimum RAM in MiB for any provisioned node */
        private int minRamMib = 4096;
        /** Maximum RAM in MiB for any provisioned node */
        private int maxRamMib = 65536;
        /**
         * If node stays NotReady for this many minutes after provisioning → replace it.
         * Also used for kubelet heartbeat timeout (fixed: 15 min).
         */
        private int nodeReadinessTimeoutMinutes = 15;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public int getHeadroomCpuPercent() { return headroomCpuPercent; }
        public void setHeadroomCpuPercent(int v) { this.headroomCpuPercent = v; }
        public int getHeadroomMemoryPercent() { return headroomMemoryPercent; }
        public void setHeadroomMemoryPercent(int v) { this.headroomMemoryPercent = v; }
        public int getMinCpuCores() { return minCpuCores; }
        public void setMinCpuCores(int v) { this.minCpuCores = v; }
        public int getMaxCpuCores() { return maxCpuCores; }
        public void setMaxCpuCores(int v) { this.maxCpuCores = v; }
        public int getMinRamMib() { return minRamMib; }
        public void setMinRamMib(int v) { this.minRamMib = v; }
        public int getMaxRamMib() { return maxRamMib; }
        public void setMaxRamMib(int v) { this.maxRamMib = v; }
        public int getNodeReadinessTimeoutMinutes() { return nodeReadinessTimeoutMinutes; }
        public void setNodeReadinessTimeoutMinutes(int v) { this.nodeReadinessTimeoutMinutes = v; }
    }

    public static class EvictorPolicyDto {
        private boolean enabled = true;
        /** Allow eviction of single-replica deployments (aggressive). Default: false. */
        private boolean aggressiveMode = false;
        /** How often the bin-packing simulation runs (seconds). Default: 60s. */
        private int nodeCycleIntervalSeconds = 60;
        /**
         * Node must be older than this before Evictor considers it.
         * Prevents evicting newly provisioned nodes. Default: 5 min.
         */
        private int nodeGracePeriodMinutes = 5;
        /** Target CPU utilization for consolidation decisions */
        private int targetUtilizationCpu = 80;
        /** Target memory utilization for consolidation decisions */
        private int targetUtilizationMemory = 80;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public boolean isAggressiveMode() { return aggressiveMode; }
        public void setAggressiveMode(boolean v) { this.aggressiveMode = v; }
        public int getNodeCycleIntervalSeconds() { return nodeCycleIntervalSeconds; }
        public void setNodeCycleIntervalSeconds(int v) { this.nodeCycleIntervalSeconds = v; }
        public int getNodeGracePeriodMinutes() { return nodeGracePeriodMinutes; }
        public void setNodeGracePeriodMinutes(int v) { this.nodeGracePeriodMinutes = v; }
        public int getTargetUtilizationCpu() { return targetUtilizationCpu; }
        public void setTargetUtilizationCpu(int v) { this.targetUtilizationCpu = v; }
        public int getTargetUtilizationMemory() { return targetUtilizationMemory; }
        public void setTargetUtilizationMemory(int v) { this.targetUtilizationMemory = v; }
    }

    public static class WorkloadRightsizerPolicyDto {
        private boolean enabled = true;
        /**
         * CPU recommendation percentile.
         * p80 (80th percentile) = handles 80% of all observed scenarios.
         * CPU is bursty → p80 avoids over-provisioning.
         */
        private int cpuPercentile = 80;
        /**
         * Memory recommendation percentile.
         * p100 (max) = always size for peak memory.
         * OOM kills are dangerous → default max.
         */
        private int memoryPercentile = 100;
        /** Buffer added on top of percentile-based CPU recommendation */
        private int cpuOverheadPercent = 10;
        /** Buffer added on top of percentile-based memory recommendation */
        private int memoryOverheadPercent = 10;
        /**
         * Minimum delta between current request and recommendation before applying.
         * Prevents constant churn for tiny differences.
         */
        private int applyThresholdPercent = 5;
        /**
         * How many hours of historical metrics to use.
         * Min 24h required for full confidence. Max 168h (7 days).
         */
        private int lookbackHours = 72;
        /**
         * immediate = restart pod now to apply new resource request.
         * deferred = annotate pod, apply on next natural restart.
         */
        private String applyMode = "deferred";
        /**
         * When CPU spikes above percentile threshold, skip the 30-min cadence
         * and apply recommendation immediately.
         */
        private boolean surgeDetection = true;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public int getCpuPercentile() { return cpuPercentile; }
        public void setCpuPercentile(int v) { this.cpuPercentile = v; }
        public int getMemoryPercentile() { return memoryPercentile; }
        public void setMemoryPercentile(int v) { this.memoryPercentile = v; }
        public int getCpuOverheadPercent() { return cpuOverheadPercent; }
        public void setCpuOverheadPercent(int v) { this.cpuOverheadPercent = v; }
        public int getMemoryOverheadPercent() { return memoryOverheadPercent; }
        public void setMemoryOverheadPercent(int v) { this.memoryOverheadPercent = v; }
        public int getApplyThresholdPercent() { return applyThresholdPercent; }
        public void setApplyThresholdPercent(int v) { this.applyThresholdPercent = v; }
        public int getLookbackHours() { return lookbackHours; }
        public void setLookbackHours(int v) { this.lookbackHours = v; }
        public String getApplyMode() { return applyMode; }
        public void setApplyMode(String v) { this.applyMode = v; }
        public boolean isSurgeDetection() { return surgeDetection; }
        public void setSurgeDetection(boolean v) { this.surgeDetection = v; }
    }

    public static class NodeTemplateDto {
        private String id;
        private String name;
        private boolean isDefault;
        /** on-demand | spot | mixed */
        private String capacityType;
        /** Fall back to on-demand when spot is unavailable */
        private boolean useSpotFallback;
        /** Seconds before retrying spot after falling back to on-demand */
        private int fallbackRestoreRateSeconds = 1800;
        private int minCpuCores;
        private int maxCpuCores;
        private int minRamMib;
        private int maxRamMib;
        private java.util.List<String> architectures;
        /** Use XamOps Spot Automation for this node pool */
        private boolean spotAutomationEnabled;
        private java.util.Map<String, String> customLabels;
        private java.util.List<java.util.Map<String, String>> customTaints;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public boolean isDefault() { return isDefault; }
        public void setDefault(boolean d) { this.isDefault = d; }
        public String getCapacityType() { return capacityType; }
        public void setCapacityType(String v) { this.capacityType = v; }
        public boolean isUseSpotFallback() { return useSpotFallback; }
        public void setUseSpotFallback(boolean v) { this.useSpotFallback = v; }
        public int getFallbackRestoreRateSeconds() { return fallbackRestoreRateSeconds; }
        public void setFallbackRestoreRateSeconds(int v) { this.fallbackRestoreRateSeconds = v; }
        public int getMinCpuCores() { return minCpuCores; }
        public void setMinCpuCores(int v) { this.minCpuCores = v; }
        public int getMaxCpuCores() { return maxCpuCores; }
        public void setMaxCpuCores(int v) { this.maxCpuCores = v; }
        public int getMinRamMib() { return minRamMib; }
        public void setMinRamMib(int v) { this.minRamMib = v; }
        public int getMaxRamMib() { return maxRamMib; }
        public void setMaxRamMib(int v) { this.maxRamMib = v; }
        public java.util.List<String> getArchitectures() { return architectures; }
        public void setArchitectures(java.util.List<String> v) { this.architectures = v; }
        public boolean isSpotAutomationEnabled() { return spotAutomationEnabled; }
        public void setSpotAutomationEnabled(boolean v) { this.spotAutomationEnabled = v; }
        public java.util.Map<String, String> getCustomLabels() { return customLabels; }
        public void setCustomLabels(java.util.Map<String, String> v) { this.customLabels = v; }
        public java.util.List<java.util.Map<String, String>> getCustomTaints() { return customTaints; }
        public void setCustomTaints(java.util.List<java.util.Map<String, String>> v) { this.customTaints = v; }
    }

    public static class RebalancerConfigDto {
        private boolean enabled = true;
        /** When true: failed drains are annotated + left cordoned instead of force-deleted */
        private boolean gracefulDraining = true;
        /** Per-node drain timeout in minutes. Max 60. */
        private int drainTimeoutMinutes = 20;
        private boolean scheduleEnabled = false;
        /** Cron expression for scheduled rebalancing (e.g. "0 2 * * 0" = Sunday 2am) */
        private String scheduleCron = "0 2 * * 0";
        /** ISO timestamp of last completed rebalance */
        private String lastRebalanceAt;
        /** Savings % achieved in last rebalance */
        private double lastRebalanceSavingsPercent;
        /** Duration of last rebalance in minutes */
        private int lastRebalanceDuration;
        /** idle | running | failed | completed */
        private String status = "idle";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public boolean isGracefulDraining() { return gracefulDraining; }
        public void setGracefulDraining(boolean v) { this.gracefulDraining = v; }
        public int getDrainTimeoutMinutes() { return drainTimeoutMinutes; }
        public void setDrainTimeoutMinutes(int v) { this.drainTimeoutMinutes = v; }
        public boolean isScheduleEnabled() { return scheduleEnabled; }
        public void setScheduleEnabled(boolean v) { this.scheduleEnabled = v; }
        public String getScheduleCron() { return scheduleCron; }
        public void setScheduleCron(String v) { this.scheduleCron = v; }
        public String getLastRebalanceAt() { return lastRebalanceAt; }
        public void setLastRebalanceAt(String v) { this.lastRebalanceAt = v; }
        public double getLastRebalanceSavingsPercent() { return lastRebalanceSavingsPercent; }
        public void setLastRebalanceSavingsPercent(double v) { this.lastRebalanceSavingsPercent = v; }
        public int getLastRebalanceDuration() { return lastRebalanceDuration; }
        public void setLastRebalanceDuration(int v) { this.lastRebalanceDuration = v; }
        public String getStatus() { return status; }
        public void setStatus(String v) { this.status = v; }
    }

    public static class SafetyGuardsDto {
        /**
         * Never evict pods that would violate their PodDisruptionBudget.
         * Checked before every eviction: disruptions_allowed must be > 0.
         */
        private boolean respectPodDisruptionBudgets = true;
        /**
         * Skip eviction of deployments/replicasets with only 1 replica.
         * Override via Evictor aggressiveMode.
         */
        private boolean protectSingleReplicas = true;
        /**
         * StatefulSet pods are never bin-packed (no Container Live Migration yet).
         */
        private boolean protectStatefulSets = true;
        /**
         * Nodes running pods from critical namespaces are excluded from scale-in.
         */
        private boolean protectCriticalNamespaces = true;
        private java.util.List<String> criticalNamespaces;

        public boolean isRespectPodDisruptionBudgets() { return respectPodDisruptionBudgets; }
        public void setRespectPodDisruptionBudgets(boolean v) { this.respectPodDisruptionBudgets = v; }
        public boolean isProtectSingleReplicas() { return protectSingleReplicas; }
        public void setProtectSingleReplicas(boolean v) { this.protectSingleReplicas = v; }
        public boolean isProtectStatefulSets() { return protectStatefulSets; }
        public void setProtectStatefulSets(boolean v) { this.protectStatefulSets = v; }
        public boolean isProtectCriticalNamespaces() { return protectCriticalNamespaces; }
        public void setProtectCriticalNamespaces(boolean v) { this.protectCriticalNamespaces = v; }
        public java.util.List<String> getCriticalNamespaces() { return criticalNamespaces; }
        public void setCriticalNamespaces(java.util.List<String> v) { this.criticalNamespaces = v; }
    }

    public static class AutoscalingEventDto {
        private String id;
        private String timestamp;
        /** scale-out | scale-in | rebalance | workload-resize | spot-fallback | node-replace */
        private String type;
        /** info | warning | success */
        private String severity;
        private String message;
        private String details;
        private Integer nodeCount;
        private Double savingsDelta;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getTimestamp() { return timestamp; }
        public void setTimestamp(String v) { this.timestamp = v; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getSeverity() { return severity; }
        public void setSeverity(String severity) { this.severity = severity; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public String getDetails() { return details; }
        public void setDetails(String details) { this.details = details; }
        public Integer getNodeCount() { return nodeCount; }
        public void setNodeCount(Integer nodeCount) { this.nodeCount = nodeCount; }
        public Double getSavingsDelta() { return savingsDelta; }
        public void setSavingsDelta(Double savingsDelta) { this.savingsDelta = savingsDelta; }
    }
}
