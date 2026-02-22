package com.xammer.cloud.dto.azure;

/**
 * DTO for a single Azure Advisor Cost recommendation.
 * Parsed from:
 *   GET https://management.azure.com/subscriptions/{subId}/providers/Microsoft.Advisor/recommendations
 *       ?api-version=2023-01-01&$filter=Category eq 'Cost'
 */
public class AzureRightsizingRecommendation {

    private String recommendationId;  // "name" field from Advisor JSON
    private String resourceId;        // properties.resourceMetadata.resourceId
    private String resourceName;      // last segment of resourceId
    private String resourceType;      // e.g. "virtualMachines", "disks"
    private String category;          // properties.category  (always "Cost")
    private String impact;            // properties.impact: "High" | "Medium" | "Low"
    private String currentSku;        // properties.extendedProperties.currentSku
    private String targetSku;         // properties.extendedProperties.targetSku
    private double monthlySavings;    // properties.extendedProperties.savingsAmount
    private double annualSavings;     // properties.extendedProperties.annualSavingsAmount
    private String currency;          // properties.extendedProperties.savingsCurrency
    private String problem;           // properties.shortDescription.problem
    private String solution;          // properties.shortDescription.solution
    private String maxCpuP95;         // properties.extendedProperties.maxCpuP95
    private String maxMemoryP95;      // properties.extendedProperties.maxMemoryP95
    private String location;          // parsed from resourceId if present

    // --- Getters and Setters ---

    public String getRecommendationId() { return recommendationId; }
    public void setRecommendationId(String recommendationId) { this.recommendationId = recommendationId; }

    public String getResourceId() { return resourceId; }
    public void setResourceId(String resourceId) { this.resourceId = resourceId; }

    public String getResourceName() { return resourceName; }
    public void setResourceName(String resourceName) { this.resourceName = resourceName; }

    public String getResourceType() { return resourceType; }
    public void setResourceType(String resourceType) { this.resourceType = resourceType; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getImpact() { return impact; }
    public void setImpact(String impact) { this.impact = impact; }

    public String getCurrentSku() { return currentSku; }
    public void setCurrentSku(String currentSku) { this.currentSku = currentSku; }

    public String getTargetSku() { return targetSku; }
    public void setTargetSku(String targetSku) { this.targetSku = targetSku; }

    public double getMonthlySavings() { return monthlySavings; }
    public void setMonthlySavings(double monthlySavings) { this.monthlySavings = monthlySavings; }

    public double getAnnualSavings() { return annualSavings; }
    public void setAnnualSavings(double annualSavings) { this.annualSavings = annualSavings; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public String getProblem() { return problem; }
    public void setProblem(String problem) { this.problem = problem; }

    public String getSolution() { return solution; }
    public void setSolution(String solution) { this.solution = solution; }

    public String getMaxCpuP95() { return maxCpuP95; }
    public void setMaxCpuP95(String maxCpuP95) { this.maxCpuP95 = maxCpuP95; }

    public String getMaxMemoryP95() { return maxMemoryP95; }
    public void setMaxMemoryP95(String maxMemoryP95) { this.maxMemoryP95 = maxMemoryP95; }

    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
}
