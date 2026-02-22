package com.xammer.cloud.dto.azure;

/**
 * DTO representing a Microsoft Defender for Cloud security assessment (recommendation).
 * Only Unhealthy assessments are included (ones that require action).
 * Sourced from: GET .../providers/Microsoft.Security/assessments
 */
public class AzureSecurityAssessment {

    private String assessmentId;
    private String displayName;
    private String severity;      // "High" | "Medium" | "Low"
    private String statusCode;    // "Unhealthy" | "Healthy" | "NotApplicable"
    private String resourceId;
    private String resourceName;
    private String category;
    private String remediation;

    public AzureSecurityAssessment() {}

    public AzureSecurityAssessment(String assessmentId, String displayName, String severity,
                                    String statusCode, String resourceId, String resourceName,
                                    String category, String remediation) {
        this.assessmentId = assessmentId;
        this.displayName = displayName;
        this.severity = severity;
        this.statusCode = statusCode;
        this.resourceId = resourceId;
        this.resourceName = resourceName;
        this.category = category;
        this.remediation = remediation;
    }

    public String getAssessmentId() { return assessmentId; }
    public void setAssessmentId(String assessmentId) { this.assessmentId = assessmentId; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }

    public String getStatusCode() { return statusCode; }
    public void setStatusCode(String statusCode) { this.statusCode = statusCode; }

    public String getResourceId() { return resourceId; }
    public void setResourceId(String resourceId) { this.resourceId = resourceId; }

    public String getResourceName() { return resourceName; }
    public void setResourceName(String resourceName) { this.resourceName = resourceName; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getRemediation() { return remediation; }
    public void setRemediation(String remediation) { this.remediation = remediation; }
}
