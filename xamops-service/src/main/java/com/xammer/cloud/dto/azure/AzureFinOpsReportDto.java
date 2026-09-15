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

    /**
     * Detected day-over-day cost spikes per service.
     *
     * <p>
     * The AWS and GCP FinOps reports both carry this; the Azure report had no field for it, so even
     * once anomalies were detected there was nowhere in the report payload to put them. Uses the
     * shared {@link com.xammer.cloud.dto.DashboardData.CostAnomaly} so the three providers'
     * anomaly rows are interchangeable on the client.
     */
    private List<com.xammer.cloud.dto.DashboardData.CostAnomaly> costAnomalies;

    /**
     * Why {@link #costAnomalies} is empty, or null when detection ran normally.
     *
     * <p>
     * An empty anomaly list means either "spend looks normal" or "we never checked", and the UI
     * renders both as a clean bill of health. Detection legitimately cannot run for the first week of
     * a month on subscriptions whose billing arrives via the month-to-date blob export, because there
     * is no seven-day baseline yet — so this is the common case, not an edge one.
     */
    private String costAnomaliesUnavailableReason;

    /** Why {@link #budgets} is empty, or null when Azure returned the list normally. */
    private String budgetsUnavailableReason;

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
