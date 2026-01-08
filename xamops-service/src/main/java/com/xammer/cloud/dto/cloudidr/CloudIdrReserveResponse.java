package com.xammer.cloud.dto.cloudidr;

import lombok.Data;
import java.util.List;

@Data
public class CloudIdrReserveResponse {
    private MyPolicy myPolicy;
    private boolean capacityAvailable;
    private List<String> alternativeQtyRecommendations;
    private List<String> alternativeTimeRecommendations;
    private List<String> alternativeTypeRecommendations;

    @Data
    public static class MyPolicy {
        private String cidrId;
        private String policyName;
        private String cspAccountName;
        private String policyStatus;
        private String policyOperatingState;
        private PolicyInstance policyInstance;
        private CspRegion cspRegion;
        private String createdDate;
        private CapacityConfiguration capacityConfiguration;
        private Pricing pricing;
    }

    @Data
    public static class PolicyInstance {
        private Long id;
        private String instanceType;
        private String platformCode;
        private String tenancyCode;
        private Integer vcpu;
        private Integer memory;
        private Integer gpu;
        private Integer gpuMemory;
        private Integer qty;
    }

    @Data
    public static class CspRegion {
        private String regionCode;
        private String regionType;
        private String availabilityZoneId;
    }

    @Data
    public static class CapacityConfiguration {
        private String launchDateTime;
        private String endDateTime;
        private Boolean autoRenewalEnabled;
        private String frequency;
        private String vpc;
        private String securityGroups;
        private String amiImage;
        private String sshPairKey;
        private Integer ebsVolumeSize;
    }

    @Data
    public static class Pricing {
        private Double ourPrice;
        private Double cspProviderPrice;
        private Double savings;
        private Double savingsPercentage;
        private Double creditCardProcessingFee;
        private Double bankAccountProcessingFee;
    }
}
