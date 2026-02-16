package com.xammer.cloud.dto.flexcompute;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExtendCapacityRequest {

    @JsonProperty("cidrId")
    private String cidrId; // Policy/Capacity ID to extend

    @JsonProperty("endDate")
    private String endDate; // New end date in MM/dd/yyyy format

    @JsonProperty("endTime")
    private String endTime; // New end time in HH:mm format (15-min increments: 00, 15, 30, 45)

    @JsonProperty("utcOffset")
    private String utcOffset; // UTC offset as string e.g. "-8", "5.5"
}
