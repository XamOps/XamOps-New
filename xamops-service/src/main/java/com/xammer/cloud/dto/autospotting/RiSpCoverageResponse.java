package com.xammer.cloud.dto.autospotting;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
public class RiSpCoverageResponse {

    @JsonProperty("account_id")
    private String accountId;

    @JsonProperty("collected_at")
    private String collectedAt;

    @JsonProperty("has_billing_access")
    private Boolean hasBillingAccess;

    @JsonProperty("total_ri_utilization_percent")
    private Double totalRiUtilizationPercent;

    @JsonProperty("total_sp_utilization_percent")
    private Double totalSpUtilizationPercent;

    @JsonProperty("ri_coverages")
    private List<RiCoverage> riCoverages;

    @JsonProperty("sp_coverages")
    private List<SpCoverage> spCoverages;

    @JsonProperty("ri_utilizations")
    private List<Object> riUtilizations; // Empty in sample, using Object for now

    @JsonProperty("sp_utilizations")
    private List<Object> spUtilizations; // Empty in sample, using Object for now

    @JsonProperty("underutilized_families")
    private Object underutilizedFamilies; // null in sample

    @Data
    public static class RiCoverage {
        @JsonProperty("instance_type")
        private String instanceType;

        @JsonProperty("instance_family")
        private String instanceFamily;

        @JsonProperty("region")
        private String region;

        @JsonProperty("availability_zone")
        private String availabilityZone;

        @JsonProperty("scope")
        private String scope;

        @JsonProperty("coverage_percent")
        private Double coveragePercent;
    }

    @Data
    public static class SpCoverage {
        @JsonProperty("plan_type")
        private String planType;

        @JsonProperty("coverage_percent")
        private Double coveragePercent;
    }
}
