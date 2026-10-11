package com.xammer.cloud.dto.k8s;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Container Insights state of one EKS cluster (design D3): whether its performance log group
 * exists, checked with the existing {@code DescribeLogGroups} call and an exact name match. It
 * says nothing about data freshness, which XamOps doesn't check yet (F19).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ContainerInsightsStatusDto {

    public static final String CONFIGURED = "CONFIGURED";
    public static final String NOT_CONFIGURED = "NOT_CONFIGURED";
    public static final String UNKNOWN = "UNKNOWN";

    /** CONFIGURED, NOT_CONFIGURED or UNKNOWN. */
    private String status;
    /** ISO-8601 UTC instant of the check (the cluster list is cached, so it can be older than the page). */
    private String checkedAt;
    /** MISSING_AWS_PERMISSION when the role can't describe log groups, else null. */
    private String reason;
    /** The IAM action that was denied, e.g. logs:DescribeLogGroups, else null. */
    private String action;

    public boolean isConfigured() {
        return CONFIGURED.equals(status);
    }
}
