package com.xammer.cloud.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class AIOPsDashboardDto implements Serializable {
    private static final long serialVersionUID = 1L;

    private LlmKpis kpis;
    private CostTrend costTrend;
    private List<ProviderCostBreakdown> providerBreakdown;
    private List<ModelUsageStat> modelUsageTable;
    private List<CostAnomaly> anomalies;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LlmKpis implements Serializable {
        private static final long serialVersionUID = 1L;
        private double totalSpend;
        private long requestsToday;
        private long requestsThisMonth;
        private double todayVsYesterdayPct;
        private double monthVsLastMonthPct;
        private long inputTokens;
        private long outputTokens;
        private double avgLatencyMs;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CostTrend implements Serializable {
        private static final long serialVersionUID = 1L;
        private List<String> labels;
        private List<Double> costs;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProviderCostBreakdown implements Serializable {
        private static final long serialVersionUID = 1L;
        private String provider;
        private double cost;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ModelUsageStat implements Serializable {
        private static final long serialVersionUID = 1L;
        private String model;
        private String provider;
        private long requests;
        private long totalTokens;
        private double cost;
        private double avgLatencyMs;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CostAnomaly implements Serializable {
        private static final long serialVersionUID = 1L;
        private String date;
        private String provider;
        private double actualCost;
        private double expectedCost;
        private double deviationPct;
        private String severity; // WARNING, CRITICAL
    }

    // ─── Usage Analytics DTOs ────────────────────────────────────

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RequestLogEntry implements Serializable {
        private static final long serialVersionUID = 1L;
        private Long id;
        private String timestamp;
        private String provider;
        private String model;
        private String agentName;
        private int inputTokens;
        private int outputTokens;
        private double cost;
        private long latencyMs;
        private int statusCode;
        private boolean isError;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AgentUsageStat implements Serializable {
        private static final long serialVersionUID = 1L;
        private String agentName;
        private long requests;
        private long totalTokens;
        private double cost;
        private double avgLatencyMs;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ModelComparisonStat implements Serializable {
        private static final long serialVersionUID = 1L;
        private String model;
        private String provider;
        private long requests;
        private long inputTokens;
        private long outputTokens;
        private double cost;
        private double avgLatencyMs;
        private double errorRate;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HeatmapCell implements Serializable {
        private static final long serialVersionUID = 1L;
        private int dayOfWeek;
        private int hour;
        private long totalTokens;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FilterOptions implements Serializable {
        private static final long serialVersionUID = 1L;
        private List<String> providers;
        private List<String> models;
        private List<String> agents;
    }

    // ─── All-Time Metrics DTO ──────────────────────────────────

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AllTimeMetrics implements Serializable {
        private static final long serialVersionUID = 1L;
        private double allTimeCost;
        private long allTimeTokens;
        private long allTimeRequests;
    }

    // ─── Daily Spend Entry DTO ──────────────────────────────────

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DailySpendEntry implements Serializable {
        private static final long serialVersionUID = 1L;
        private String date;
        private double spend;
    }

    // ─── Budget & Controls DTOs ─────────────────────────────────

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BudgetConfigDto implements Serializable {
        private static final long serialVersionUID = 1L;
        private Long id;
        private Long providerConfigId;
        private String providerName;
        private String budgetName;
        private double monthlyLimitUsd;
        private String alertThresholds;
        private boolean autoDisable;
        private String status;
        private String notificationEmail;
        private String createdAt;
        private String updatedAt;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BudgetStatusDto implements Serializable {
        private static final long serialVersionUID = 1L;
        private Long budgetId;
        private String budgetName;
        private Long providerConfigId;
        private String providerName;
        private double monthlyLimitUsd;
        private double currentSpendUsd;
        private double usagePct;
        private boolean autoDisable;
        private String status;
        private List<Integer> thresholds;
        private List<Integer> breachedThresholds;
        private double projectedSpend;
        private double burnRate;
        private int estimatedRunoutDay;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BudgetAlertDto implements Serializable {
        private static final long serialVersionUID = 1L;
        private Long id;
        private Long budgetId;
        private String budgetName;
        private String providerName;
        private int thresholdPct;
        private double actualSpendUsd;
        private double budgetLimitUsd;
        private String budgetMonth;
        private String triggeredAt;
        private boolean dismissed;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BudgetSummaryDto implements Serializable {
        private static final long serialVersionUID = 1L;
        private double totalBudgetUsd;
        private double totalSpendUsd;
        private double overallUsagePct;
        private int activeBudgetCount;
        private int alertsThisMonth;
        private List<BudgetStatusDto> budgets;
        private List<BudgetAlertDto> recentAlerts;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BudgetCreateRequest implements Serializable {
        private static final long serialVersionUID = 1L;
        private Long providerConfigId;
        private String budgetName;
        private double monthlyLimitUsd;
        private String alertThresholds;
        private boolean autoDisable;
        private String notificationEmail;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BudgetUpdateRequest implements Serializable {
        private static final long serialVersionUID = 1L;
        private Double monthlyLimitUsd;
        private String alertThresholds;
        private Boolean autoDisable;
        private String status;
        private String notificationEmail;
    }

    // ─── Team Analytics DTOs ────────────────────────────────────

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TeamUsageStat implements Serializable {
        private static final long serialVersionUID = 1L;
        private String teamName;
        private long requests;
        private long totalTokens;
        private double cost;
        private double avgLatencyMs;
        private int modelsUsed;
        private double costPct;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TeamCostTrend implements Serializable {
        private static final long serialVersionUID = 1L;
        private List<String> labels;
        private Map<String, List<Double>> seriesByTeam;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TeamAnalyticsSummary implements Serializable {
        private static final long serialVersionUID = 1L;
        private List<TeamUsageStat> teams;
        private TeamCostTrend costTrend;
        private int totalTeams;
        private double totalCost;
    }

    // ─── Performance Monitoring DTOs ─────────────────────────────

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LatencyPercentiles implements Serializable {
        private static final long serialVersionUID = 1L;
        private String model;
        private String provider;
        private double p50;
        private double p95;
        private double p99;
        private double avgLatencyMs;
        private long totalRequests;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ErrorRateTrendPoint implements Serializable {
        private static final long serialVersionUID = 1L;
        private String date;
        private long totalRequests;
        private long errors;
        private double errorRate;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LatencyBucket implements Serializable {
        private static final long serialVersionUID = 1L;
        private String bucket;
        private long count;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ModelReliability implements Serializable {
        private static final long serialVersionUID = 1L;
        private String model;
        private String provider;
        private long totalRequests;
        private long errorCount;
        private double errorRate;
        private double avgLatencyMs;
        private double reliabilityScore;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PerformanceMetrics implements Serializable {
        private static final long serialVersionUID = 1L;
        private List<LatencyPercentiles> latencyByModel;
        private List<ErrorRateTrendPoint> errorRateTrend;
        private List<LatencyBucket> latencyDistribution;
        private List<ModelReliability> modelReliability;
    }

    // ─── AI Insights DTOs ────────────────────────────────────────

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InsightCard implements Serializable {
        private static final long serialVersionUID = 1L;
        private String id;
        private String type;         // COST_OPTIMIZATION, USAGE_PATTERN, ANOMALY, PERFORMANCE, TREND
        private String severity;     // INFO, WARNING, ACTIONABLE
        private String title;
        private String description;
        private String impact;
        private boolean actionable;
        private String actionLabel;
        private Map<String, Object> metadata;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InsightsSummary implements Serializable {
        private static final long serialVersionUID = 1L;
        private List<InsightCard> insights;
        private int totalInsights;
        private int actionableCount;
        private double estimatedSavingsUsd;
        private String generatedAt;
    }

    // ─── Routing / Cost Optimizer DTOs ───────────────────────────

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RoutingRuleDto implements Serializable {
        private static final long serialVersionUID = 1L;
        private Long id;
        private String ruleName;
        private String sourceModel;
        private String targetModel;
        private String conditionType;
        private String conditionValue;
        private int priority;
        private boolean enabled;
        private String createdAt;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RoutingRuleCreateRequest implements Serializable {
        private static final long serialVersionUID = 1L;
        private String ruleName;
        private String sourceModel;
        private String targetModel;
        private String conditionType;
        private String conditionValue;
        private int priority;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RoutingStatsDto implements Serializable {
        private static final long serialVersionUID = 1L;
        private long totalRoutedRequests;
        private double estimatedSavingsUsd;
        private double actualSpendUsd;
        private double wouldHaveSpentUsd;
        private List<RoutingRuleDto> rules;
        private List<RoutingSavingsEntry> savingsByRule;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RoutingSavingsEntry implements Serializable {
        private static final long serialVersionUID = 1L;
        private String sourceModel;
        private String targetModel;
        private long routedCount;
        private double actualCost;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RoutingHistoryEntry implements Serializable {
        private static final long serialVersionUID = 1L;
        private Long id;
        private String timestamp;
        private String routedFrom;
        private String routedTo;
        private String agentName;
        private int inputTokens;
        private int outputTokens;
        private double cost;
    }

    // ─── API Key Management DTOs ────────────────────────────────

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ApiKeyStats implements Serializable {
        private static final long serialVersionUID = 1L;
        private Long id;
        private String provider;
        private String displayName;
        private String maskedKey;
        private String status;
        private String healthStatus;   // HEALTHY, DEGRADED, ERROR, UNKNOWN
        private long requestCount;
        private long errorCount;
        private String lastUsedAt;
        private String lastErrorAt;
        private String lastErrorMessage;
        private Long usageLimit;
        private String createdAt;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ApiKeyRotateRequest implements Serializable {
        private static final long serialVersionUID = 1L;
        private String newApiKey;
        private String newAwsAccessKey;
        private String newAwsSecretKey;
    }

    // ─── Compute / GPU Metrics DTOs ─────────────────────────────

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ComputeUsageByProvider implements Serializable {
        private static final long serialVersionUID = 1L;
        private String provider;
        private long totalRequests;
        private long totalInputTokens;
        private long totalOutputTokens;
        private double totalCost;
        private double avgLatencyMs;
        private double computeTimeSec;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ComputeDailyTrend implements Serializable {
        private static final long serialVersionUID = 1L;
        private List<String> labels;
        private List<Long> inputTokens;
        private List<Long> outputTokens;
        private List<Double> computeTimeSec;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ModelComputeAllocation implements Serializable {
        private static final long serialVersionUID = 1L;
        private String model;
        private String provider;
        private long requests;
        private long totalTokens;
        private double cost;
        private double computeTimeSec;
        private double pctOfTotalCompute;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ComputeMetricsSummary implements Serializable {
        private static final long serialVersionUID = 1L;
        private long totalRequests;
        private long totalTokens;
        private double totalComputeTimeSec;
        private double totalCost;
        private List<ComputeUsageByProvider> providerBreakdown;
        private ComputeDailyTrend dailyTrend;
        private List<ModelComputeAllocation> modelAllocation;
    }

    // ─── Managed Inference DTOs ─────────────────────────────────

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InferenceEndpoint implements Serializable {
        private static final long serialVersionUID = 1L;
        private Long id;
        private String provider;
        private String model;
        private String displayName;
        private String status;       // ACTIVE, DEGRADED, DOWN, INACTIVE
        private long totalRequests;
        private double totalCost;
        private double avgLatencyMs;
        private double errorRate;
        private double uptimePct;
        private String lastRequestAt;
        private String createdAt;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InferenceOverview implements Serializable {
        private static final long serialVersionUID = 1L;
        private int totalEndpoints;
        private int activeEndpoints;
        private int degradedEndpoints;
        private double totalCost;
        private double avgLatency;
        private List<InferenceEndpoint> endpoints;
    }
}
