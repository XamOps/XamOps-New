package com.xammer.cloud.dto.flexcompute;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
public class LinkedAccountResponse {

    @JsonProperty("id")
    private Long id; // CloudiDR internal ID

    @JsonProperty("cspAccountId")
    private String cspAccountId; // Cloud provider account identifier (e.g., "605134457560")

    @JsonProperty("alias")
    private String alias; // User-defined name for the account (e.g., "Xammer")

    @JsonProperty("cspProvider")
    private CspProvider cspProvider; // Cloud provider details

    @JsonProperty("status")
    private String status; // Account status (e.g., "LINKED", "FAILED")

    @JsonProperty("isDefault")
    private Boolean isDefault; // Whether this is the default account

    @JsonProperty("linkedAt")
    private String linkedAt; // ISO 8601 timestamp when account was linked

    @JsonProperty("accountRegions")
    private List<AccountRegion> accountRegions; // Available regions for this account

    // Constructor for backward compatibility with existing service code
    public LinkedAccountResponse(String accountId, String accountName, String provider, String status, String linkedAt) {
        this.cspAccountId = accountId;
        this.alias = accountName;
        if (provider != null) {
            this.cspProvider = new CspProvider(null, provider);
        }
        this.status = status;
        this.linkedAt = linkedAt;
    }

    // Helper methods to match existing code expectations
    public String getAccountId() {
        return cspAccountId;
    }

    public String getAccountName() {
        return alias;
    }

    public String getProvider() {
        return cspProvider != null ? cspProvider.getProviderCode() : null;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CspProvider {
        @JsonProperty("id")
        private Long id;

        @JsonProperty("providerCode")
        private String providerCode; // e.g., "AWS"
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AccountRegion {
        @JsonProperty("id")
        private Long id;

        @JsonProperty("operatingCspRegionAz")
        private OperatingCspRegionAz operatingCspRegionAz;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OperatingCspRegionAz {
        @JsonProperty("regionCode")
        private String regionCode; // e.g., "us-west-2"

        @JsonProperty("regionType")
        private String regionType; // e.g., "PRIMARY"

        @JsonProperty("availabilityZoneId")
        private String availabilityZoneId; // e.g., "usw2-az1"
    }
}
