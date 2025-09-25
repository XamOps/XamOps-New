package com.xammer.cloud.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class XamOpsRightsizingRecommendation {

    private String currentInstance;
    private String loadRange;
    private String intelRecommendation;
    private String amdRecommendation;
    private String projectedMaxUtil;
    private String approxCostSavings;
    private String reason;
}