package com.xammer.cloud.dto.azure;

import java.util.List;

/**
 * Wrapper response for Azure Advisor rightsizing recommendations.
 * Includes a status field so the frontend can distinguish between
 * permission errors, empty results, and successful fetches.
 */
public class AzureRightsizingResponse {

    public enum Status {
        OK,               // 200 with 1+ recommendations
        NO_DATA,          // 200 but 0 recommendations
        PERMISSION_ERROR, // 403 from Azure Advisor
        API_ERROR         // other HTTP error or exception
    }

    private Status status;
    private List<AzureRightsizingRecommendation> recommendations;

    public AzureRightsizingResponse() {}

    public AzureRightsizingResponse(Status status, List<AzureRightsizingRecommendation> recommendations) {
        this.status = status;
        this.recommendations = recommendations;
    }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public List<AzureRightsizingRecommendation> getRecommendations() { return recommendations; }
    public void setRecommendations(List<AzureRightsizingRecommendation> recommendations) {
        this.recommendations = recommendations;
    }
}
