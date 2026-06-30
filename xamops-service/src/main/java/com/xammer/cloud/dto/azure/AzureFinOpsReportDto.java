package com.xammer.cloud.dto.azure;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
public class AzureFinOpsReportDto {

    private Kpis kpis;
    private List<CostBreakdown> costBreakdown;
    private List<CostByRegion> costByRegion;
    private double totalPotentialSavings;
    private List<RightsizingItem> rightsizingItems;
    private List<WasteItem> wasteItems;
    private List<AzureBudgetDto> budgets;

    // Inner class for KPIs
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Kpis {
        private double totalSpend;
        private double forecastedSpend;
        private double yoyChange;
        private double momChange;
    }

    // Inner class for Cost Breakdown
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CostBreakdown {
        private String service;
        private double cost;
        private double change; // MoM change
    }

    // Inner class for Cost by Region
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CostByRegion {
        private String region;
        private double cost;
    }

    // Inner class for Rightsizing items (mapped from AzureRightsizingRecommendation)
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RightsizingItem {
        private String resourceName;
        private String resourceType;
        private String currentSku;
        private String targetSku;
        private double monthlySavings;
        private double annualSavings;
        private String impact;
        private String location;
    }

    // Inner class for Waste items (mapped from AzureWasteItem)
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WasteItem {
        private String resourceId;
        private String resourceName;
        private String resourceType;
        private String region;
        private String reason;
        private double monthlySavings;
    }
}
