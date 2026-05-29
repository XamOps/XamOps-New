package com.xammer.cloud.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class SavingsPlanRecommendationDto {
    private String savingsPlanType;
    private String region;
    private String instanceFamily;
    private String recommendedCommitment;
    private String upfrontCost;
    private String estimatedMonthlyCost;
    private String estimatedMonthlySavings;
    private String estimatedSavingsPercentage;
    private String currentAverageHourlyOnDemandSpend;
}
