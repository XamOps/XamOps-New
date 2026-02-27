package com.xammer.cloud.dto.autoscaling;

import java.util.List;

/**
 * DTO for the rightsizing recommendations tab in the Autoscaling UI.
 * Populated from agent_data_cache (dataType = "autoscaling-rightsizing")
 * pushed by the xamops-autoscaler controller.
 */
public class WorkloadRightsizingDto {

    private List<WorkloadRecommendation> recommendations;
    private String lastAnalyzedAt;
    private int totalWorkloads;
    private int workloadsWithRecommendations;
    private double totalEstimatedSavingsPercent;

    public List<WorkloadRecommendation> getRecommendations() { return recommendations; }
    public void setRecommendations(List<WorkloadRecommendation> r) { this.recommendations = r; }
    public String getLastAnalyzedAt() { return lastAnalyzedAt; }
    public void setLastAnalyzedAt(String v) { this.lastAnalyzedAt = v; }
    public int getTotalWorkloads() { return totalWorkloads; }
    public void setTotalWorkloads(int v) { this.totalWorkloads = v; }
    public int getWorkloadsWithRecommendations() { return workloadsWithRecommendations; }
    public void setWorkloadsWithRecommendations(int v) { this.workloadsWithRecommendations = v; }
    public double getTotalEstimatedSavingsPercent() { return totalEstimatedSavingsPercent; }
    public void setTotalEstimatedSavingsPercent(double v) { this.totalEstimatedSavingsPercent = v; }

    public static class WorkloadRecommendation {
        private String namespace;
        private String workloadName;
        private String workloadKind;     // Deployment | StatefulSet | DaemonSet
        private String containerName;
        private String currentCpuRequest;
        private String currentMemRequest;
        private String recommendedCpu;
        private String recommendedMem;
        private double p80Cpu;
        private double p100Mem;
        private double estimatedSavingsPercent;
        private boolean applied;
        private String appliedAt;
        private String reason;

        public String getNamespace() { return namespace; }
        public void setNamespace(String v) { this.namespace = v; }
        public String getWorkloadName() { return workloadName; }
        public void setWorkloadName(String v) { this.workloadName = v; }
        public String getWorkloadKind() { return workloadKind; }
        public void setWorkloadKind(String v) { this.workloadKind = v; }
        public String getContainerName() { return containerName; }
        public void setContainerName(String v) { this.containerName = v; }
        public String getCurrentCpuRequest() { return currentCpuRequest; }
        public void setCurrentCpuRequest(String v) { this.currentCpuRequest = v; }
        public String getCurrentMemRequest() { return currentMemRequest; }
        public void setCurrentMemRequest(String v) { this.currentMemRequest = v; }
        public String getRecommendedCpu() { return recommendedCpu; }
        public void setRecommendedCpu(String v) { this.recommendedCpu = v; }
        public String getRecommendedMem() { return recommendedMem; }
        public void setRecommendedMem(String v) { this.recommendedMem = v; }
        public double getP80Cpu() { return p80Cpu; }
        public void setP80Cpu(double v) { this.p80Cpu = v; }
        public double getP100Mem() { return p100Mem; }
        public void setP100Mem(double v) { this.p100Mem = v; }
        public double getEstimatedSavingsPercent() { return estimatedSavingsPercent; }
        public void setEstimatedSavingsPercent(double v) { this.estimatedSavingsPercent = v; }
        public boolean isApplied() { return applied; }
        public void setApplied(boolean v) { this.applied = v; }
        public String getAppliedAt() { return appliedAt; }
        public void setAppliedAt(String v) { this.appliedAt = v; }
        public String getReason() { return reason; }
        public void setReason(String v) { this.reason = v; }
    }
}
