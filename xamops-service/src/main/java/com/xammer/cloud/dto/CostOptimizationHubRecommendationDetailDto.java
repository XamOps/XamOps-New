package com.xammer.cloud.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Drill-down detail for a single Cost Optimization Hub recommendation, backing the row-click
 * detail view on the AWS Rightsizing page.
 *
 * <p>Extends the list item with the full current-vs-recommended configuration breakdown. The Hub
 * {@code GetRecommendation} response carries {@code currentResourceDetails} and
 * {@code recommendedResourceDetails} as a 25-member union (ec2Instance, ebsVolume, lambdaFunction,
 * ec2AutoScalingGroup, ecsService, rds*, savingsPlans, reservedInstances, …). Rather than model
 * every shape, each side is flattened into an ordered {@code key -> value} map of configuration and
 * cost fields, which keeps the drill-down populated for every recommendation type without a brittle
 * 25-way switch.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
public class CostOptimizationHubRecommendationDetailDto extends CostOptimizationHubRecommendationDto {

    /** Flattened current-resource configuration + cost breakdown (before). Insertion-ordered. */
    private Map<String, String> currentResourceDetails = new LinkedHashMap<>();
    /** Flattened recommended-resource configuration + cost breakdown (after). Insertion-ordered. */
    private Map<String, String> recommendedResourceDetails = new LinkedHashMap<>();

    /** Lookback window the recommendation itself was computed over, in days. */
    private Integer recommendationLookbackPeriodInDays;
    /** Lookback window the cost/savings figures were computed over, in days. */
    private Integer costCalculationLookbackPeriodInDays;
    /** Estimated savings over the full cost-calculation lookback period. */
    private Double estimatedSavingsOverCostCalculationLookbackPeriod;
}
