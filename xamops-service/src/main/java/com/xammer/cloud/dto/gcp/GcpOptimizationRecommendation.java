package com.xammer.cloud.dto.gcp;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for representing a single GCP rightsizing recommendation.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GcpOptimizationRecommendation {
    /**
     * The name of the resource that can be rightsized.
     * e.g., "gce-instance-1" or "basic-mysql"
     */
    private String resourceName;

    /**
     * The current machine type of the instance.
     * e.g., "n1-standard-4" or "db-f1-micro (shared vCPU, 0.6 GB)"
     */
    private String currentMachineType;

    /**
     * The recommended machine type for the instance.
     * e.g., "n1-standard-2" or "db-custom-1-3840 (1 vCPU, 3.75 GB)"
     */
    private String recommendedMachineType;

    /**
     * The estimated potential savings per month in USD.
     * This is the calculated *savings* (current_price - recommended_price).
     * A negative value indicates a cost increase (for underprovisioned resources).
     */
    private double monthlySavings;

    /**
     * The service the recommendation is for (e.g., "Compute Engine", "Cloud SQL").
     */
    private String service;

    /**
     * The location/region of the resource.
     */
    private String location;

    /**
     * The recommendation ID from GCP.
     */
    private String recommendationId;

    /**
     * Type of recommendation: "COST_SAVINGS", "PERFORMANCE_IMPROVEMENT", "OPTIMIZATION"
     */
    private String recommendationType;

    /**
     * Detailed description/reason for the recommendation from GCP.
     */
    private String reason;

    /**
     * The actual cost of this specific resource over the last 30 days, if available.
     * Populated from BigQuery billing export.
     */
    private Double last30DayCost;

    // Constructor with 'reason' (9 args)
    public GcpOptimizationRecommendation(String resourceName, String currentMachineType,
                                         String recommendedMachineType, double monthlySavings,
                                         String service, String location, String recommendationId,
                                         String recommendationType, String reason) {
        this.resourceName = resourceName;
        this.currentMachineType = currentMachineType;
        this.recommendedMachineType = recommendedMachineType;
        this.monthlySavings = monthlySavings;
        this.service = service;
        this.location = location;
        this.recommendationId = recommendationId;
        this.recommendationType = recommendationType;
        this.reason = reason;
        this.last30DayCost = null; // Set new field to null
    }

    // Constructor for backward compatibility (8 args)
    public GcpOptimizationRecommendation(String resourceName, String currentMachineType,
                                         String recommendedMachineType, double monthlySavings,
                                         String service, String location, String recommendationId,
                                         String recommendationType) {
        this(resourceName, currentMachineType, recommendedMachineType, monthlySavings,
                service, location, recommendationId, recommendationType, "");
    }

    // Constructor for even older backward compatibility (5 args)
    public GcpOptimizationRecommendation(String resourceName, String currentMachineType,
                                         String recommendedMachineType, double monthlySavings, String service) {
        this(resourceName, currentMachineType, recommendedMachineType, monthlySavings,
                service, "global", "",
                monthlySavings >= 0 ? "COST_SAVINGS" : "PERFORMANCE_IMPROVEMENT", "");
    }

    /**
     * Returns true if this recommendation will result in a cost increase.
     */
    public boolean isCostIncrease() {
        return monthlySavings < 0;
    }

    /**
     * Returns the absolute value of monthly cost impact.
     */
    public double getAbsoluteMonthlyCost() {
        return Math.abs(monthlySavings);
    }

    /**
     * Get a concise summary of the recommendation reason.
     * Limits to first 100 characters for display purposes.
     */
    public String getReasonSummary() {
        if (reason == null || reason.isEmpty()) {
            return "No details available";
        }
        return reason.length() > 100 ? reason.substring(0, 97) + "..." : reason;
    }
}