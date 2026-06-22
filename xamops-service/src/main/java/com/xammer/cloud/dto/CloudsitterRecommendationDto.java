package com.xammer.cloud.dto;

import java.util.List;
import java.util.Map;

public class CloudsitterRecommendationDto {

    /** Hours the instance should be RUNNING per day (same format as scheduleJson). */
    private Map<String, List<Integer>> recommendedSchedule;

    /** Average CPU p95% per slot — 7 days × 24 hours. */
    private Map<String, List<Double>> avgCpuGrid;

    /** Average memory% per slot — populated only when CWAgent is installed. */
    private Map<String, List<Double>> avgMemGrid;

    /** Average NetworkPacketsIn per slot. */
    private Map<String, List<Double>> avgNetGrid;

    /** Combined EBS read+write ops per slot. */
    private Map<String, List<Double>> avgDiskGrid;

    private boolean memAvailable;
    private boolean netAvailable;
    private boolean diskAvailable;

    private int dataPoints;
    private int lookbackDays;

    /** dataPoints / (lookbackDays * 24) — low values mean the recommendation is less reliable. */
    private double confidenceScore;

    /** True if the instance has an Elastic IP (customer-allocated, not AWS-dynamic). */
    private boolean hasEip;

    private double cpuIdleThreshold;
    private double memIdleThreshold;
    private double networkIdleThreshold;
    private double diskIdleThreshold;

    /**
     * Null when a full recommendation was computed.
     * "ASG_MANAGED"       — instance is in an Auto Scaling Group; stopping it causes immediate replacement.
     * "K8S_NODE"          — instance is a Kubernetes node; critical infrastructure that should run 24/7.
     * "PRODUCTION"        — instance is tagged as production environment; should not be scheduled.
     * "ACTIVE_ALARM"      — instance has active CloudWatch alarms; indicates potential issues.
     * "INSUFFICIENT_DATA" — fewer than 50% of expected data points were available.
     */
    private String skipReason;

    public CloudsitterRecommendationDto() {}

    public CloudsitterRecommendationDto(
            Map<String, List<Integer>> recommendedSchedule,
            Map<String, List<Double>> avgCpuGrid,
            Map<String, List<Double>> avgMemGrid,
            Map<String, List<Double>> avgNetGrid,
            Map<String, List<Double>> avgDiskGrid,
            boolean memAvailable,
            boolean netAvailable,
            boolean diskAvailable,
            int dataPoints,
            int lookbackDays,
            double confidenceScore,
            boolean hasEip,
            double cpuIdleThreshold,
            double memIdleThreshold,
            double networkIdleThreshold,
            double diskIdleThreshold) {
        this.recommendedSchedule  = recommendedSchedule;
        this.avgCpuGrid           = avgCpuGrid;
        this.avgMemGrid           = avgMemGrid;
        this.avgNetGrid           = avgNetGrid;
        this.avgDiskGrid          = avgDiskGrid;
        this.memAvailable         = memAvailable;
        this.netAvailable         = netAvailable;
        this.diskAvailable        = diskAvailable;
        this.dataPoints           = dataPoints;
        this.lookbackDays         = lookbackDays;
        this.confidenceScore      = confidenceScore;
        this.hasEip               = hasEip;
        this.cpuIdleThreshold     = cpuIdleThreshold;
        this.memIdleThreshold     = memIdleThreshold;
        this.networkIdleThreshold = networkIdleThreshold;
        this.diskIdleThreshold    = diskIdleThreshold;
    }

    /** Returns a DTO that signals the recommendation was skipped and why. */
    public static CloudsitterRecommendationDto skipped(String reason) {
        CloudsitterRecommendationDto dto = new CloudsitterRecommendationDto();
        dto.skipReason = reason;
        return dto;
    }

    public Map<String, List<Integer>> getRecommendedSchedule() { return recommendedSchedule; }
    public void setRecommendedSchedule(Map<String, List<Integer>> v) { this.recommendedSchedule = v; }

    public Map<String, List<Double>> getAvgCpuGrid() { return avgCpuGrid; }
    public void setAvgCpuGrid(Map<String, List<Double>> v) { this.avgCpuGrid = v; }

    public Map<String, List<Double>> getAvgMemGrid() { return avgMemGrid; }
    public void setAvgMemGrid(Map<String, List<Double>> v) { this.avgMemGrid = v; }

    public Map<String, List<Double>> getAvgNetGrid() { return avgNetGrid; }
    public void setAvgNetGrid(Map<String, List<Double>> v) { this.avgNetGrid = v; }

    public Map<String, List<Double>> getAvgDiskGrid() { return avgDiskGrid; }
    public void setAvgDiskGrid(Map<String, List<Double>> v) { this.avgDiskGrid = v; }

    public boolean isMemAvailable() { return memAvailable; }
    public void setMemAvailable(boolean v) { this.memAvailable = v; }

    public boolean isNetAvailable() { return netAvailable; }
    public void setNetAvailable(boolean v) { this.netAvailable = v; }

    public boolean isDiskAvailable() { return diskAvailable; }
    public void setDiskAvailable(boolean v) { this.diskAvailable = v; }

    public int getDataPoints() { return dataPoints; }
    public void setDataPoints(int v) { this.dataPoints = v; }

    public int getLookbackDays() { return lookbackDays; }
    public void setLookbackDays(int v) { this.lookbackDays = v; }

    public double getConfidenceScore() { return confidenceScore; }
    public void setConfidenceScore(double v) { this.confidenceScore = v; }

    public boolean isHasEip() { return hasEip; }
    public void setHasEip(boolean v) { this.hasEip = v; }

    public double getCpuIdleThreshold() { return cpuIdleThreshold; }
    public void setCpuIdleThreshold(double v) { this.cpuIdleThreshold = v; }

    public double getMemIdleThreshold() { return memIdleThreshold; }
    public void setMemIdleThreshold(double v) { this.memIdleThreshold = v; }

    public double getNetworkIdleThreshold() { return networkIdleThreshold; }
    public void setNetworkIdleThreshold(double v) { this.networkIdleThreshold = v; }

    public double getDiskIdleThreshold() { return diskIdleThreshold; }
    public void setDiskIdleThreshold(double v) { this.diskIdleThreshold = v; }

    public String getSkipReason() { return skipReason; }
    public void setSkipReason(String v) { this.skipReason = v; }

    /** @deprecated use getCpuIdleThreshold() */
    public double getIdleThreshold() { return cpuIdleThreshold; }
}
