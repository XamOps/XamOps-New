package com.xammer.cloud.dto.gcp;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for representing a single GCP security finding.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GcpSecurityFinding {
    /**
     * The category of the finding.
     * e.g., "IAM", "Networking", "Storage"
     */
    private String category;

    /**
     * A detailed description of the security finding.
     */
    private String description;

    /**
     * The severity level of the finding.
     * e.g., "HIGH", "MEDIUM", "LOW"
     */
    private String severity;

    /**
     * The full name of the resource associated with the finding.
     */
    private String resourceName;

    /**
     * SOC2 Trust Service Criteria control mapped to this finding.
     * e.g., "CC6.1", "CC6.7", "CC7.1". Null if no SOC2 mapping applies.
     */
    private String complianceControl;
}
