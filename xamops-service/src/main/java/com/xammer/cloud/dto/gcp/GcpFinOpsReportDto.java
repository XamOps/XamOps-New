package com.xammer.cloud.dto.gcp;

import com.xammer.cloud.dto.DashboardData;
import lombok.Data;
import java.util.List;

@Data
public class GcpFinOpsReportDto {

    private Kpis kpis;
    private CostBreakdown costBreakdown;
    private List<GcpOptimizationRecommendation> rightsizingRecommendations;
    private List<GcpWasteItem> wastedResources;
    private List<DashboardData.CostAnomaly> costAnomalies;
    private List<GcpBudgetDto> budgets;

    @Data
    public static class Kpis {
        private double monthToDateSpend;
        private double lastMonthSpend;
        private double forecastedSpend;
        private double potentialSavings;
    }

    @Data
    public static class CostBreakdown {
        private List<GcpCostDto> byService;
        private List<GcpCostDto> byRegion;
    }
}