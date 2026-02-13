package com.xammer.cloud.dto.flexcompute;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ReserveCapacityResponse {

    @JsonProperty("myPolicy")
    private MyPolicy myPolicy;

    @JsonProperty("capacityAvailable")
    private Boolean capacityAvailable;

    @JsonProperty("alternativeQtyRecommendations")
    private Object[] alternativeQtyRecommendations;

    @JsonProperty("alternativeTimeRecommendations")
    private Object[] alternativeTimeRecommendations;

    @JsonProperty("alternativeTypeRecommendations")
    private Object[] alternativeTypeRecommendations;

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MyPolicy {
        @JsonProperty("cidrId")
        private String cidrId; // e.g., "sc_830349911839"

        @JsonProperty("policyName")
        private String policyName;

        @JsonProperty("cspAccountName")
        private String cspAccountName;

        @JsonProperty("policyStatus")
        private String policyStatus; // e.g., "READY_FOR_PURCHASE"

        @JsonProperty("policyOperatingState")
        private String policyOperatingState; // e.g., "IDLE"

        @JsonProperty("policyInstance")
        private PolicyInstance policyInstance;

        @JsonProperty("cspRegion")
        private CspRegion cspRegion;

        @JsonProperty("createdDate")
        private String createdDate;

        @JsonProperty("capacityConfiguration")
        private CapacityConfiguration capacityConfiguration;

        @JsonProperty("pricing")
        private Pricing pricing;
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PolicyInstance {
        @JsonProperty("id")
        private Integer id;

        @JsonProperty("instanceType")
        private String instanceType;

        @JsonProperty("platformCode")
        private String platformCode;

        @JsonProperty("tenancyCode")
        private String tenancyCode;

        @JsonProperty("vcpu")
        private Integer vcpu;

        @JsonProperty("memory")
        private Integer memory;

        @JsonProperty("gpu")
        private Integer gpu;

        @JsonProperty("gpuMemory")
        private Integer gpuMemory;

        @JsonProperty("qty")
        private Integer qty;
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CspRegion {
        @JsonProperty("regionCode")
        private String regionCode;

        @JsonProperty("regionType")
        private String regionType;

        @JsonProperty("availabilityZoneId")
        private String availabilityZoneId;
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CapacityConfiguration {
        @JsonProperty("launchDateTime")
        private String launchDateTime;

        @JsonProperty("endDateTime")
        private String endDateTime;

        @JsonProperty("autoRenewalEnabled")
        private Boolean autoRenewalEnabled;

        @JsonProperty("frequency")
        private String frequency;

        @JsonProperty("vpc")
        private String vpc;

        @JsonProperty("securityGroups")
        private String securityGroups;

        @JsonProperty("amiImage")
        private String amiImage;

        @JsonProperty("sshPairKey")
        private String sshPairKey;

        @JsonProperty("ebsVolumeSize")
        private Integer ebsVolumeSize;
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Pricing {
        @JsonProperty("ourPrice")
        private Double ourPrice;

        @JsonProperty("cspProviderPrice")
        private Double cspProviderPrice;

        @JsonProperty("savings")
        private Double savings;

        @JsonProperty("savingsPercentage")
        private Double savingsPercentage;

        @JsonProperty("creditCardProcessingFee")
        private Double creditCardProcessingFee;

        @JsonProperty("bankAccountProcessingFee")
        private Double bankAccountProcessingFee;
    }
}
