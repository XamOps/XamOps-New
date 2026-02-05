package com.xammer.cloud.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for representing a single AWS SecurityHub finding.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AwsSecurityFinding {
    /**
     * The unique identifier for the finding.
     */
    private String id;

    /**
     * The title of the security finding.
     */
    private String title;

    /**
     * A detailed description of the security finding.
     */
    private String description;

    /**
     * The severity level of the finding.
     * e.g., "CRITICAL", "HIGH", "MEDIUM", "LOW", "INFORMATIONAL"
     */
    private String severity;

    /**
     * The category/type of the finding.
     * e.g., "Software and Configuration Checks"
     */
    private String category;

    /**
     * The type of the affected resource.
     * e.g., "AwsEc2Instance", "AwsS3Bucket"
     */
    private String resourceType;

    /**
     * The ARN or ID of the affected resource.
     */
    private String resourceId;

    /**
     * The AWS region where the finding was detected.
     */
    private String region;

    /**
     * The compliance status of the finding.
     * e.g., "PASSED", "FAILED", "WARNING", "NOT_AVAILABLE"
     */
    private String complianceStatus;

    /**
     * The product that generated the finding.
     * e.g., "Security Hub", "GuardDuty", "Inspector"
     */
    private String productName;

    /**
     * The recommended remediation action.
     */
    private String remediation;
}
