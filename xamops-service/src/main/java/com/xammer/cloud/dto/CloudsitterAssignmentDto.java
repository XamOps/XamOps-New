package com.xammer.cloud.dto;

import com.xammer.cloud.domain.CloudsitterPolicy;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for CloudSitter assignment responses that includes calculated savings.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CloudsitterAssignmentDto {
    private Long id;
    private String resourceId;
    private String accountId;
    private String region;
    private CloudsitterPolicy policy;
    private boolean active;

    /**
     * Estimated monthly compute saving in USD, valid only when {@link #savingsUnknown} is
     * false.
     *
     * <p>Covers compute only. Attached EBS volumes and any associated public IPv4 address
     * continue to bill while the instance is stopped, so this is not a reduction in the
     * instance's total cost.
     */
    private double monthlySavings;

    /** Resolved instance type, or {@code "unknown"} when it could not be determined. */
    private String instanceType;

    /**
     * True when the instance type could not be resolved, so no saving could be priced.
     * Consumers must exclude these from totals rather than showing zero as a real figure.
     */
    private boolean savingsUnknown;
}
