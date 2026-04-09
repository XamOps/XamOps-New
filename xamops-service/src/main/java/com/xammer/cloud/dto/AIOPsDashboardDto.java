package com.xammer.cloud.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

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
}
