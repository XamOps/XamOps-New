package com.xammer.cloud.dto.azure;

import java.util.List;

/**
 * Wrapper response for Azure Advisor Performance recommendations.
 * Includes a status field and computed performance score.
 */
public class AzurePerformanceResponse {

    public enum Status {
        OK,               // 200 with 1+ insights
        NO_DATA,          // 200 but 0 insights (no performance issues)
        PERMISSION_ERROR, // 403 — Advisor Reviews Reader role required
        API_ERROR         // other HTTP error or exception
    }

    private Status status;
    private int performanceScore; // 100 - (High×10) - (Medium×5) - (Low×2), min 0
    private List<AzurePerformanceInsight> insights;

    public AzurePerformanceResponse() {}

    public AzurePerformanceResponse(Status status, int performanceScore,
                                     List<AzurePerformanceInsight> insights) {
        this.status = status;
        this.performanceScore = performanceScore;
        this.insights = insights;
    }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public int getPerformanceScore() { return performanceScore; }
    public void setPerformanceScore(int performanceScore) { this.performanceScore = performanceScore; }

    public List<AzurePerformanceInsight> getInsights() { return insights; }
    public void setInsights(List<AzurePerformanceInsight> insights) { this.insights = insights; }
}
