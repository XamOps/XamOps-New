package com.xammer.cloud.dto.autospotting;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class EventRecord {

    @JsonProperty("timestamp")
    private String timestamp;

    @JsonProperty("event_type")
    private String eventType;

    @JsonProperty("account_id")
    private String accountId;

    @JsonProperty("region")
    private String region;

    @JsonProperty("asg_name")
    private String asgName;

    @JsonProperty("old_instance_id")
    private String oldInstanceId;

    @JsonProperty("old_instance_type")
    private String oldInstanceType;

    @JsonProperty("old_lifecycle")
    private String oldLifecycle;

    @JsonProperty("new_instance_id")
    private String newInstanceId;

    @JsonProperty("new_instance_type")
    private String newInstanceType;

    @JsonProperty("new_lifecycle")
    private String newLifecycle;

    @JsonProperty("reason")
    private String reason;

    @JsonProperty("estimated_savings")
    private Double estimatedSavings;

    @JsonProperty("old_hourly_price")
    private Double oldHourlyPrice;

    @JsonProperty("new_hourly_price")
    private Double newHourlyPrice;
}
