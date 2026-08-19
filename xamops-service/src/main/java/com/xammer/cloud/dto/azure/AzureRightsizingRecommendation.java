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
    /**
     * Highest observed CPU utilization, as a formatted percentage.
     *
     * <p>Renamed from {@code maxCpuP95}: for metric-based recommendations this service
     * stores the true Azure Monitor {@code Maximum}, not a 95th percentile, and labelling a
     * maximum as p95 misrepresented how much headroom a downsize would leave.
     */
    private String maxCpuObserved;

    /** Average observed CPU utilization, as a formatted percentage. */
    private String avgCpuObserved;

    /**
     * Highest observed memory utilization, as a formatted percentage. Populated only when
     * Advisor supplies it; Azure does not expose guest memory without an in-VM agent.
     */
    private String maxMemoryObserved;

    /**
     * True when a saving could not be quantified, so {@link #monthlySavings} is a
     * placeholder zero. Consumers must exclude these from savings totals.
     */
    private boolean savingsUnknown;

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

    public String getMaxCpuObserved() { return maxCpuObserved; }
    public void setMaxCpuObserved(String maxCpuObserved) { this.maxCpuObserved = maxCpuObserved; }

    public String getAvgCpuObserved() { return avgCpuObserved; }
    public void setAvgCpuObserved(String avgCpuObserved) { this.avgCpuObserved = avgCpuObserved; }

    public String getMaxMemoryObserved() { return maxMemoryObserved; }
    public void setMaxMemoryObserved(String maxMemoryObserved) { this.maxMemoryObserved = maxMemoryObserved; }

    public boolean isSavingsUnknown() { return savingsUnknown; }
    public void setSavingsUnknown(boolean savingsUnknown) { this.savingsUnknown = savingsUnknown; }

    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
}
