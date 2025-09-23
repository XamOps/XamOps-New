package com.xammer.cloud.dto.azure;

import java.util.List;
import lombok.Data;

@Data
public class AzureDashboardData {

    private ResourceInventory resourceInventory;
    private CostHistory costHistory;
    private List<BillingSummary> billingSummary;
    private List<RegionStatus> regionStatus;
    private OptimizationSummary optimizationSummary;
    private List<RightsizingRecommendation> vmRecommendations;
    private List<RightsizingRecommendation> diskRecommendations;
    private List<RightsizingRecommendation> functionRecommendations;
    private List<CostAnomaly> costAnomalies;

    @Data
    public static class ResourceInventory {
        private long virtualMachines;
        private long storageAccounts;
        private long sqlDatabases;
        private long virtualNetworks;
        private long functions;
        private long disks;
        private long dnsZones;
        private long loadBalancers;
        private long containerInstances;
        private long kubernetesServices;
        private long appServices;
        private long staticWebApps;
    }

    @Data
    public static class CostHistory {
        private List<String> labels;
        private List<Double> costs;
        private List<Boolean> anomalies;
    }

    @Data
    public static class BillingSummary {
        private String serviceName;
        private Double monthToDateCost;

        public BillingSummary(String serviceName, Double monthToDateCost) {
            this.serviceName = serviceName;
            this.monthToDateCost = monthToDateCost;
        }
    }

    @Data
    public static class RegionStatus {
        private String regionId;
        private Double latitude;
        private Double longitude;
        private String status;

        public RegionStatus(String regionId, Double latitude, Double longitude, String status) {
            this.regionId = regionId;
            this.latitude = latitude;
            this.longitude = longitude;
            this.status = status;
        }
    }

    @Data
    public static class OptimizationSummary {
        private Double totalPotentialSavings = 0.0;
        private int criticalAlertsCount = 0;
    }

    @Data
    public static class RightsizingRecommendation {
        private String resourceId;
        private String currentType;
        private String recommendedType;
        private Double estimatedMonthlySavings;
    }

    @Data
    public static class CostAnomaly {
        private String service;
        private String startDate;
        private Double unexpectedSpend;
    }
}