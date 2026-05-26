package com.xammer.cloud.dto.cicd;

import java.util.List;

public class CicdMetricsDto {
    // Current period KPIs
    private double successRate;
    private int totalRuns;
    private int failureCount;
    private long avgDurationSeconds;

    // Previous period KPIs (for delta arrows)
    private double prevSuccessRate;
    private int prevTotalRuns;
    private int prevFailureCount;
    private long prevAvgDurationSeconds;

    // DORA metrics (null for Jenkins)
    private CicdDoraDto dora;

    // Daily statistics
    private List<CicdDailyStatDto> dailyStats;

    // Top failing components
    private List<FailingStageDto> topFailingStages;
    private List<FailingWorkflowDto> topFailingWorkflows;

    public CicdMetricsDto() {
    }

    // Getters and Setters
    public double getSuccessRate() {
        return successRate;
    }

    public void setSuccessRate(double successRate) {
        this.successRate = successRate;
    }

    public int getTotalRuns() {
        return totalRuns;
    }

    public void setTotalRuns(int totalRuns) {
        this.totalRuns = totalRuns;
    }

    public int getFailureCount() {
        return failureCount;
    }

    public void setFailureCount(int failureCount) {
        this.failureCount = failureCount;
    }

    public long getAvgDurationSeconds() {
        return avgDurationSeconds;
    }

    public void setAvgDurationSeconds(long avgDurationSeconds) {
        this.avgDurationSeconds = avgDurationSeconds;
    }

    public double getPrevSuccessRate() {
        return prevSuccessRate;
    }

    public void setPrevSuccessRate(double prevSuccessRate) {
        this.prevSuccessRate = prevSuccessRate;
    }

    public int getPrevTotalRuns() {
        return prevTotalRuns;
    }

    public void setPrevTotalRuns(int prevTotalRuns) {
        this.prevTotalRuns = prevTotalRuns;
    }

    public int getPrevFailureCount() {
        return prevFailureCount;
    }

    public void setPrevFailureCount(int prevFailureCount) {
        this.prevFailureCount = prevFailureCount;
    }

    public long getPrevAvgDurationSeconds() {
        return prevAvgDurationSeconds;
    }

    public void setPrevAvgDurationSeconds(long prevAvgDurationSeconds) {
        this.prevAvgDurationSeconds = prevAvgDurationSeconds;
    }

    public CicdDoraDto getDora() {
        return dora;
    }

    public void setDora(CicdDoraDto dora) {
        this.dora = dora;
    }

    public List<CicdDailyStatDto> getDailyStats() {
        return dailyStats;
    }

    public void setDailyStats(List<CicdDailyStatDto> dailyStats) {
        this.dailyStats = dailyStats;
    }

    public List<FailingStageDto> getTopFailingStages() {
        return topFailingStages;
    }

    public void setTopFailingStages(List<FailingStageDto> topFailingStages) {
        this.topFailingStages = topFailingStages;
    }

    public List<FailingWorkflowDto> getTopFailingWorkflows() {
        return topFailingWorkflows;
    }

    public void setTopFailingWorkflows(List<FailingWorkflowDto> topFailingWorkflows) {
        this.topFailingWorkflows = topFailingWorkflows;
    }

    // Nested static class: FailingStageDto
    public static class FailingStageDto {
        private String name;
        private int failureCount;

        public FailingStageDto() {}

        public FailingStageDto(String name, int failureCount) {
            this.name = name;
            this.failureCount = failureCount;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getFailureCount() {
            return failureCount;
        }

        public void setFailureCount(int failureCount) {
            this.failureCount = failureCount;
        }
    }

    // Nested static class: FailingWorkflowDto
    public static class FailingWorkflowDto {
        private String name;
        private int failureCount;
        private double successRate;

        public FailingWorkflowDto() {}

        public FailingWorkflowDto(String name, int failureCount, double successRate) {
            this.name = name;
            this.failureCount = failureCount;
            this.successRate = successRate;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getFailureCount() {
            return failureCount;
        }

        public void setFailureCount(int failureCount) {
            this.failureCount = failureCount;
        }

        public double getSuccessRate() {
            return successRate;
        }

        public void setSuccessRate(double successRate) {
            this.successRate = successRate;
        }
    }
}
