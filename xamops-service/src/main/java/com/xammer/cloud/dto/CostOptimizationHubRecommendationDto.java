package com.xammer.cloud.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * One AWS Cost Optimization Hub recommendation, as shown in the list on the AWS Rightsizing page.
 *
 * <p>Maps a {@code software.amazon.awssdk.services.costoptimizationhub.model.Recommendation}. The
 * Hub is a cross-service, cross-region recommendation feed (rightsizing, idle-resource deletion,
 * Savings Plans / Reserved Instances, architecture migration), distinct from the per-service
 * Compute Optimizer / Cost Explorer recommendations the page already surfaces.
 */
@Data
@NoArgsConstructor
public class CostOptimizationHubRecommendationDto {

    private String recommendationId;
    private String accountId;

    /** e.g. {@code Rightsize}, {@code Stop}, {@code Delete}, {@code Upgrade}, {@code MigrateToGraviton}. */
    private String actionType;

    private String currentResourceType;
    private String currentResourceSummary;
    private String recommendedResourceType;
    private String recommendedResourceSummary;

    private String resourceArn;
    private String resourceId;
    private String region;

    /** Estimated monthly savings in {@link #currencyCode}. Nullable when the Hub did not report it. */
    private Double estimatedMonthlySavings;
    /** Estimated savings as a percentage of current monthly cost. */
    private Double estimatedSavingsPercentage;
    /** Current estimated monthly cost of the resource. */
    private Double estimatedMonthlyCost;

    /** e.g. {@code VeryLow}, {@code Low}, {@code Medium}, {@code High}, {@code VeryHigh}. */
    private String implementationEffort;
    private Boolean restartNeeded;
    private Boolean rollbackPossible;

    private String currencyCode;
    /** ISO-8601 timestamp of the last Hub refresh, as a string for safe JSON round-tripping. */
    private String lastRefreshTimestamp;

    private Map<String, String> tags;
}
