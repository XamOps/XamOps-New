package com.xammer.cloud.dto.flexcompute;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class SearchCapacityResponse {

    @JsonProperty("capacity_id")
    private String capacityId;

    @JsonProperty("capacity_name")
    private String capacityName;

    @JsonProperty("instance_type")
    private String instanceType;

    @JsonProperty("region")
    private String region;

    @JsonProperty("zone_id")
    private String zoneId;

    @JsonProperty("quantity")
    private Integer quantity;

    @JsonProperty("platform")
    private String platform;

    @JsonProperty("tenancy")
    private String tenancy;

    @JsonProperty("our_price")
    private Double ourPrice;

    @JsonProperty("aws_price")
    private Double awsPrice;

    @JsonProperty("savings_percent")
    private Double savingsPercent;

    @JsonProperty("fees")
    private Double fees;

    @JsonProperty("total")
    private Double total;

    @JsonProperty("start_date")
    private String startDate;

    @JsonProperty("end_date")
    private String endDate;

    @JsonProperty("status")
    private String status;
}
