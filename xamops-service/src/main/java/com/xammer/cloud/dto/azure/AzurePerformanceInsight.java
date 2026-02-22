package com.xammer.cloud.dto.azure;

/**
 * DTO representing an Azure Advisor Performance recommendation.
 * Sourced from: GET .../providers/Microsoft.Advisor/recommendations?$filter=Category eq 'Performance'
 */
public class AzurePerformanceInsight {

    private String insightId;
    private String resourceName;
    private String resourceType;
    private String region;
    private String severity;      // "High" | "Medium" | "Low" (from Advisor impact)
    private String description;   // shortDescription.problem
    private String recommendation; // shortDescription.solution

    public AzurePerformanceInsight() {}

    public AzurePerformanceInsight(String insightId, String resourceName, String resourceType,
                                    String region, String severity, String description,
                                    String recommendation) {
        this.insightId = insightId;
        this.resourceName = resourceName;
        this.resourceType = resourceType;
        this.region = region;
        this.severity = severity;
        this.description = description;
        this.recommendation = recommendation;
    }

    public String getInsightId() { return insightId; }
    public void setInsightId(String insightId) { this.insightId = insightId; }

    public String getResourceName() { return resourceName; }
    public void setResourceName(String resourceName) { this.resourceName = resourceName; }

    public String getResourceType() { return resourceType; }
    public void setResourceType(String resourceType) { this.resourceType = resourceType; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getRecommendation() { return recommendation; }
    public void setRecommendation(String recommendation) { this.recommendation = recommendation; }
}
