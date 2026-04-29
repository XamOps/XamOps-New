package com.xammer.cloud.dto;

import java.util.List;
import java.util.Map;

public class CloudsitterRecommendationDto {

    /** Hours the instance should be RUNNING per day (same format as scheduleJson). */
    private Map<String, List<Integer>> recommendedSchedule;

    /** Average CPU% per slot — 7 days × 24 hours — for heatmap rendering. */
    private Map<String, List<Double>> avgCpuGrid;

    /**
     * Average memory% per slot — populated only when the CloudWatch Agent is
     * installed on the instance (CWAgent / mem_used_percent).
     */
    private Map<String, List<Double>> avgMemGrid;

    /** False when the CWAgent is not installed and memory data is unavailable. */
    private boolean memAvailable;

    private int dataPoints;
    private int lookbackDays;
    private double cpuIdleThreshold;
    private double memIdleThreshold;

    public CloudsitterRecommendationDto() {}

    public CloudsitterRecommendationDto(
            Map<String, List<Integer>> recommendedSchedule,
            Map<String, List<Double>> avgCpuGrid,
            Map<String, List<Double>> avgMemGrid,
            boolean memAvailable,
            int dataPoints,
            int lookbackDays,
            double cpuIdleThreshold,
            double memIdleThreshold) {
        this.recommendedSchedule = recommendedSchedule;
        this.avgCpuGrid = avgCpuGrid;
        this.avgMemGrid = avgMemGrid;
        this.memAvailable = memAvailable;
        this.dataPoints = dataPoints;
        this.lookbackDays = lookbackDays;
        this.cpuIdleThreshold = cpuIdleThreshold;
        this.memIdleThreshold = memIdleThreshold;
    }

    public Map<String, List<Integer>> getRecommendedSchedule() { return recommendedSchedule; }
    public void setRecommendedSchedule(Map<String, List<Integer>> v) { this.recommendedSchedule = v; }

    public Map<String, List<Double>> getAvgCpuGrid() { return avgCpuGrid; }
    public void setAvgCpuGrid(Map<String, List<Double>> v) { this.avgCpuGrid = v; }

    public Map<String, List<Double>> getAvgMemGrid() { return avgMemGrid; }
    public void setAvgMemGrid(Map<String, List<Double>> v) { this.avgMemGrid = v; }

    public boolean isMemAvailable() { return memAvailable; }
    public void setMemAvailable(boolean v) { this.memAvailable = v; }

    public int getDataPoints() { return dataPoints; }
    public void setDataPoints(int v) { this.dataPoints = v; }

    public int getLookbackDays() { return lookbackDays; }
    public void setLookbackDays(int v) { this.lookbackDays = v; }

    public double getCpuIdleThreshold() { return cpuIdleThreshold; }
    public void setCpuIdleThreshold(double v) { this.cpuIdleThreshold = v; }

    public double getMemIdleThreshold() { return memIdleThreshold; }
    public void setMemIdleThreshold(double v) { this.memIdleThreshold = v; }

    /** @deprecated use getCpuIdleThreshold() */
    public double getIdleThreshold() { return cpuIdleThreshold; }
}
