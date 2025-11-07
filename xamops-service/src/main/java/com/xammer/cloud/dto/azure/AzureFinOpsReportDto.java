package com.xammer.cloud.dto.azure;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
public class AzureFinOpsReportDto {

    private Kpis kpis;
    private List<CostBreakdown> costBreakdown;
    private List<CostByRegion> costByRegion; // NEW

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

    // NEW: Inner class for Cost by Region
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CostByRegion {
        private String region;
        private double cost;
    }
}
