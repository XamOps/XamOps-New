package com.xammer.cloud.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class XamOpsRightsizingRecommendation {
    private String currentInstance;         // Instance type (e.g., "t3.medium")
    private String instanceId;              // Actual instance ID
    private String currentUtilization;      //Actual CPU utilization
    private String loadRange;               // Load range (e.g., "0-50%")
    private String intelRecommendation;     // Intel-based recommendation
    private String amdRecommendation;       // AMD-based recommendation
    private String projectedMaxUtil;        // Projected max utilization
    private String approxCostSavings;       // Cost savings estimate
    private String reason;                  // Recommendation reason
}
