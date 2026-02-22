package com.xammer.cloud.dto.azure;

/**
 * DTO representing a single Azure Monitor alert from the AlertsManagement API.
 *
 * Fields are parsed from properties.essentials in the API response.
 * Severity: Sev0=Critical, Sev1=Error, Sev2=Warning, Sev3=Informational, Sev4=Verbose
 * AlertState: New, Acknowledged, Closed
 * MonitorCondition: Fired, Resolved
 */
public class AzureMonitorAlert {

    private String alertId;        // node.name (GUID)
    private String alertName;      // last segment of essentials.alertRule resource path
    private String description;    // essentials.description
    private String severity;       // "Sev0" | "Sev1" | "Sev2" | "Sev3" | "Sev4"
    private String severityLabel;  // "Critical" | "Error" | "Warning" | "Informational" | "Verbose"
    private String alertState;     // "New" | "Acknowledged" | "Closed"
    private String monitorCondition; // "Fired" | "Resolved"
    private String signalType;     // "Metric" | "Log" | "Activity Log" | "Health"
    private String resourceName;   // essentials.targetResourceName
    private String resourceGroup;  // essentials.targetResourceGroup
    private String resourceType;   // last segment of essentials.targetResourceType
    private String startDateTime;  // essentials.startDateTime (ISO-8601)

    public AzureMonitorAlert() {}

    public String getAlertId() { return alertId; }
    public void setAlertId(String alertId) { this.alertId = alertId; }

    public String getAlertName() { return alertName; }
    public void setAlertName(String alertName) { this.alertName = alertName; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }

    public String getSeverityLabel() { return severityLabel; }
    public void setSeverityLabel(String severityLabel) { this.severityLabel = severityLabel; }

    public String getAlertState() { return alertState; }
    public void setAlertState(String alertState) { this.alertState = alertState; }

    public String getMonitorCondition() { return monitorCondition; }
    public void setMonitorCondition(String monitorCondition) { this.monitorCondition = monitorCondition; }

    public String getSignalType() { return signalType; }
    public void setSignalType(String signalType) { this.signalType = signalType; }

    public String getResourceName() { return resourceName; }
    public void setResourceName(String resourceName) { this.resourceName = resourceName; }

    public String getResourceGroup() { return resourceGroup; }
    public void setResourceGroup(String resourceGroup) { this.resourceGroup = resourceGroup; }

    public String getResourceType() { return resourceType; }
    public void setResourceType(String resourceType) { this.resourceType = resourceType; }

    public String getStartDateTime() { return startDateTime; }
    public void setStartDateTime(String startDateTime) { this.startDateTime = startDateTime; }
}
