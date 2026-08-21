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
        /**
         * True when the saving could not be quantified, so {@link #monthlySavings} is a
         * placeholder zero. Was previously dropped during mapping, so a deliberately
         * unpriced recommendation rendered as a definitive $0.00.
         */
        private boolean savingsUnknown;
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
        /** Real cost that could not be priced; exclude from totals. */
        private boolean savingsUnknown;
        /** Azure does not bill for this resource; exclude from reclaimable-spend totals. */
        private boolean noCostImpact;

        /** True when this item should contribute to a reclaimable-spend total. */
        @com.fasterxml.jackson.annotation.JsonIgnore
        public boolean isBillableWaste() {
            return !savingsUnknown && !noCostImpact;
        }
    }
}
