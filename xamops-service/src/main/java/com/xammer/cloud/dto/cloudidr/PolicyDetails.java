package com.xammer.cloud.dto.cloudidr;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true) // Prevents crashes on unknown fields
public class PolicyDetails {
    private String cidrId;
    private String policyName;
    private String policyStatus;
    private String policyOperatingState;
    private String cspAccountName;
    private String cspAccountId;

    private PolicyInstance policyInstance;
    private CspRegion cspRegion;

    @JsonProperty("pricing")
    private PricingDetails pricing;

    // Nested configuration object from API
    private CapacityConfiguration capacityConfiguration;

    // CHANGED: API returns ISO Date String (e.g. "2026-01-06T..."), not Long
    private String createdDate;

    // Helper getters for frontend compatibility
    public String getStartDate() {
        return capacityConfiguration != null ? capacityConfiguration.getLaunchDateTime() : null;
    }

    public String getEndDate() {
        return capacityConfiguration != null ? capacityConfiguration.getEndDateTime() : null;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PolicyInstance {
        private Long id; // Added missing ID field
        private String instanceType;

        @JsonAlias({ "platform", "platformCode" })
        private String platformCode;

        @JsonAlias({ "quantity", "qty" })
        private Integer qty;

        @JsonAlias({ "tenancy", "tenancyCode" })
        private String tenancyCode;

        private Integer vcpu;
        private Integer memory;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CspRegion {
        private String regionCode;
        private String regionType;
        private String availabilityZoneId;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PricingDetails {
        private Double ourPrice;
        private Double cspProviderPrice;
        private Double savings;
        private Double savingsPercentage;

        @JsonAlias("totalCost")
        public Double getTotalCost() {
            return ourPrice;
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CapacityConfiguration {
        private String launchDateTime;
        private String endDateTime;
    }
}