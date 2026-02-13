package com.xammer.cloud.dto.flexcompute;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SearchCapacityRequest {

    // Required parameters
    @JsonProperty("instanceType")
    private String instanceType; // e.g., "c5.large"

    @JsonProperty("zoneId")
    private String zoneId; // e.g., "usw2-az1"

    @JsonProperty("startDate")
    private String startDate; // Format: MM/dd/yyyy

    @JsonProperty("endDate")
    private String endDate; // Format: MM/dd/yyyy

    @JsonProperty("utcOffsetHours")
    private Integer utcOffsetHours; // Range: -18 to 18

    // Optional parameters
    @JsonProperty("platform")
    private String platform; // Defaults to "Linux/UNIX"
}
