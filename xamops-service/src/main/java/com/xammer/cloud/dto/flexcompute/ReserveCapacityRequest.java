package com.xammer.cloud.dto.flexcompute;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReserveCapacityRequest {

    // Required parameters
    @JsonProperty("accountId")
    private String accountId; // AWS account ID

    @JsonProperty("policyName")
    private String policyName; // Policy name designation

    @JsonProperty("instanceType")
    private String instanceType; // e.g., "c5.large"

    @JsonProperty("platform")
    private String platform; // OS/platform type

    @JsonProperty("zoneId")
    private String zoneId; // Availability Zone identifier

    @JsonProperty("quantity")
    private Integer quantity; // Number of instances to reserve (minimum 1)

    @JsonProperty("startDate")
    private String startDate; // Format: MM/dd/yyyy

    @JsonProperty("endDate")
    private String endDate; // Format: MM/dd/yyyy

    @JsonProperty("startTime")
    private String startTime; // Format: HH:mm (24-hour, 15-minute increments)

    @JsonProperty("endTime")
    private String endTime; // Format: HH:mm (24-hour, 15-minute increments)

    @JsonProperty("utcOffsetHours")
    private String utcOffsetHours; // UTC offset range -18 to 18
}
