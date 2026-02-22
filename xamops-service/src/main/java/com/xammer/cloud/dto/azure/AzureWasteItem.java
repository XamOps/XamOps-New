package com.xammer.cloud.dto.azure;

/**
 * DTO representing a single wasteful/idle Azure resource detected by AzureWasteService.
 */
public class AzureWasteItem {

    private String resourceId;
    private String resourceName;
    private String resourceType;   // "Unattached Disk" | "Stopped VM" | "Deallocated VM" | "Orphaned Public IP" | "Unattached NIC"
    private String region;
    private String reason;
    private double monthlySavings; // estimated monthly savings in INR if resource is removed

    public AzureWasteItem() {}

    public AzureWasteItem(String resourceId, String resourceName, String resourceType,
                          String region, String reason, double monthlySavings) {
        this.resourceId = resourceId;
        this.resourceName = resourceName;
        this.resourceType = resourceType;
        this.region = region;
        this.reason = reason;
        this.monthlySavings = monthlySavings;
    }

    public String getResourceId() { return resourceId; }
    public void setResourceId(String resourceId) { this.resourceId = resourceId; }

    public String getResourceName() { return resourceName; }
    public void setResourceName(String resourceName) { this.resourceName = resourceName; }

    public String getResourceType() { return resourceType; }
    public void setResourceType(String resourceType) { this.resourceType = resourceType; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public double getMonthlySavings() { return monthlySavings; }
    public void setMonthlySavings(double monthlySavings) { this.monthlySavings = monthlySavings; }
}
