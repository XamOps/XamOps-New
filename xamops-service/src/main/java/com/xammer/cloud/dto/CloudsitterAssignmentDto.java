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
    private double monthlySavings;
    private String instanceType;
}
