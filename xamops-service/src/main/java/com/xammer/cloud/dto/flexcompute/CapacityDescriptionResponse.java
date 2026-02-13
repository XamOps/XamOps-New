package com.xammer.cloud.dto.flexcompute;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CapacityDescriptionResponse {

    @JsonProperty("cidrId")
    private String cidrId; // e.g., "sc_528103388090"

    @JsonProperty("policyName")
    private String policyName; // User-defined name

    @JsonProperty("cspAccountName")
    private String cspAccountName; // e.g., "Xammer"

    @JsonProperty("policyStatus")
    private String policyStatus; // e.g., "EXPIRED", "COMPLETED", "CANCELED"

    @JsonProperty("policyOperatingState")
    private String policyOperatingState; // e.g., "EXPIRED_NOT_PURCHASED"

    @JsonProperty("policyInstance")
    private PolicyInstance policyInstance;

    @JsonProperty("cspRegion")
    private CspRegion cspRegion;

    @JsonProperty("createdDate")
    private String createdDate; // ISO 8601 timestamp

    @JsonProperty("capacityConfiguration")
    private CapacityConfiguration capacityConfiguration;

    @JsonProperty("pricing")
    private Pricing pricing;

    // Helper methods for backward compatibility and frontend mapping
    @JsonGetter("id")
    public String getId() {
        return cidrId;
    }

    @JsonGetter("name")
    public String getName() {
        return policyName;
    }

    @JsonGetter("accountName")
    public String getAccountName() {
        return cspAccountName;
    }

    @JsonGetter("instanceState")
    public String getInstanceState() {
        return policyOperatingState;
    }

    @JsonGetter("instanceType")
    public String getInstanceType() {
        return policyInstance != null ? policyInstance.getInstanceType() : null;
    }

    @JsonGetter("quantities")
    public Integer getQuantities() {
        return policyInstance != null ? policyInstance.getQty() : null;
    }

    @JsonGetter("regionZoneId")
    public String getRegionZoneId() {
        String region = cspRegion != null ? cspRegion.getRegionCode() : "";
        String zone = cspRegion != null ? cspRegion.getAvailabilityZoneId() : "";
        return region + (zone != null && !zone.isEmpty() ? " / " + zone : "");
    }

    @JsonGetter("estimatedSavings")
    public Double getEstimatedSavings() {
        return pricing != null ? pricing.getSavings() : 0.0;
    }

    @JsonGetter("totalSavings")
    public Double getTotalSavings() {
        return pricing != null ? pricing.getSavings() : 0.0;
    }

    @JsonGetter("totalCost")
    public Double getTotalCost() {
        return pricing != null ? pricing.getOurPrice() : 0.0;
    }

    @JsonGetter("startDate")
    public String getStartDate() {
        return capacityConfiguration != null ? capacityConfiguration.getLaunchDateTime() : null;
    }

    @JsonGetter("endDate")
    public String getEndDate() {
        return capacityConfiguration != null ? capacityConfiguration.getEndDateTime() : null;
    }

    @JsonGetter("autoRenewal")
    public Boolean getAutoRenewal() {
        return capacityConfiguration != null ? capacityConfiguration.getAutoRenewalEnabled() : false;
    }

    @JsonGetter("totalUsageHours")
    public Double getTotalUsageHours() {
        if (capacityConfiguration == null
            || capacityConfiguration.getLaunchDateTime() == null
            || capacityConfiguration.getEndDateTime() == null) {
            return 0.0;
        }

        try {
            // Parse ISO 8601 timestamps
            DateTimeFormatter formatter = DateTimeFormatter.ISO_DATE_TIME;
            LocalDateTime start = LocalDateTime.parse(capacityConfiguration.getLaunchDateTime(), formatter);
            LocalDateTime end = LocalDateTime.parse(capacityConfiguration.getEndDateTime(), formatter);

            // Calculate hours between start and end
            long hours = ChronoUnit.HOURS.between(start, end);
            return (double) hours;
        } catch (Exception e) {
            // If parsing fails, return 0
            return 0.0;
        }
    }

    @JsonGetter("remainingHours")
    public Double getRemainingHours() {
        if (capacityConfiguration == null || capacityConfiguration.getEndDateTime() == null) {
            return 0.0;
        }

        try {
            // Parse end date
            DateTimeFormatter formatter = DateTimeFormatter.ISO_DATE_TIME;
            LocalDateTime end = LocalDateTime.parse(capacityConfiguration.getEndDateTime(), formatter);
            LocalDateTime now = LocalDateTime.now();

            // Calculate remaining hours from now to end
            long hours = ChronoUnit.HOURS.between(now, end);

            // Return 0 if already expired (negative hours)
            return hours > 0 ? (double) hours : 0.0;
        } catch (Exception e) {
            return 0.0;
        }
    }

    // Additional helper methods (not serialized)
    public String getRegion() {
        return cspRegion != null ? cspRegion.getRegionCode() : null;
    }

    public String getZoneId() {
        return cspRegion != null ? cspRegion.getAvailabilityZoneId() : null;
    }

    public String getPlatform() {
        return policyInstance != null ? policyInstance.getPlatformCode() : null;
    }

    public String getTenancy() {
        return policyInstance != null ? policyInstance.getTenancyCode() : null;
    }

    public Double getOurPrice() {
        return pricing != null ? pricing.getOurPrice() : null;
    }

    public Double getAwsPrice() {
        return pricing != null ? pricing.getCspProviderPrice() : null;
    }

    public Double getSavingsPercent() {
        return pricing != null ? pricing.getSavingsPercentage() : null;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PolicyInstance {
        @JsonProperty("id")
        private Long id;

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
    @AllArgsConstructor
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
    @AllArgsConstructor
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
    @AllArgsConstructor
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
