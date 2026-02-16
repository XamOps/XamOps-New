package com.xammer.cloud.dto.flexcompute;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for updating capacity auto-renewal settings
 * PUT /external/api/v1/flexcompute/capacity/auto-renewal
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RenewCapacityRequest {

    @JsonProperty("cidrId")
    private String cidrId; // Policy identifier (e.g., "sc_238017929606")

    @JsonProperty("autoRenewalEnabled")
    private Boolean autoRenewalEnabled; // Enable/disable auto-renewal

    @JsonProperty("frequency")
    private String frequency; // Renewal interval: "one-day", "one-week", or "one-month"
}
