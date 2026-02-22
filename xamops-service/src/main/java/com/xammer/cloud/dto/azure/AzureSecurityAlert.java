package com.xammer.cloud.dto.azure;

/**
 * DTO representing a Microsoft Defender for Cloud security alert.
 * Sourced from: GET .../providers/Microsoft.Security/alerts
 */
public class AzureSecurityAlert {

    private String alertId;
    private String alertName;
    private String severity;       // "High" | "Medium" | "Low" | "Informational"
    private String status;         // "Active" | "Dismissed" | "Resolved"
    private String resourceName;
    private String description;
    private String timeGenerated;

    public AzureSecurityAlert() {}

    public AzureSecurityAlert(String alertId, String alertName, String severity, String status,
                               String resourceName, String description, String timeGenerated) {
        this.alertId = alertId;
        this.alertName = alertName;
        this.severity = severity;
        this.status = status;
        this.resourceName = resourceName;
        this.description = description;
        this.timeGenerated = timeGenerated;
    }

    public String getAlertId() { return alertId; }
    public void setAlertId(String alertId) { this.alertId = alertId; }

    public String getAlertName() { return alertName; }
    public void setAlertName(String alertName) { this.alertName = alertName; }

    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getResourceName() { return resourceName; }
    public void setResourceName(String resourceName) { this.resourceName = resourceName; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getTimeGenerated() { return timeGenerated; }
    public void setTimeGenerated(String timeGenerated) { this.timeGenerated = timeGenerated; }
}
