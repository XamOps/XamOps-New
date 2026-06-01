package com.xammer.cloud.dto.cicd;

public class CicdDailyStatDto {
    private String date;
    private int successCount;
    private int failureCount;
    private long avgDuration;

    public CicdDailyStatDto() {
    }

    public CicdDailyStatDto(String date, int successCount, int failureCount, long avgDuration) {
        this.date = date;
        this.successCount = successCount;
        this.failureCount = failureCount;
        this.avgDuration = avgDuration;
    }

    // Getters and Setters
    public String getDate() {
        return date;
    }

    public void setDate(String date) {
        this.date = date;
    }

    public int getSuccessCount() {
        return successCount;
    }

    public void setSuccessCount(int successCount) {
        this.successCount = successCount;
    }

    public int getFailureCount() {
        return failureCount;
    }

    public void setFailureCount(int failureCount) {
        this.failureCount = failureCount;
    }

    public long getAvgDuration() {
        return avgDuration;
    }

    public void setAvgDuration(long avgDuration) {
        this.avgDuration = avgDuration;
    }
}
