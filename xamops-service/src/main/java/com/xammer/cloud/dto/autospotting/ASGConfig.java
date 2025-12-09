package com.xammer.cloud.dto.autospotting;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class ASGConfig {

    @JsonProperty("asg_name")
    private String asgName;

    @JsonProperty("region")
    private String region;

    @JsonProperty("enabled")
    private Boolean enabled;

    @JsonProperty("min_on_demand")
    private Integer minOnDemand;

    @JsonProperty("on_demand_percentage_above_base_capacity")
    private Integer onDemandPercentageAboveBaseCapacity;

    @JsonProperty("allowed_instance_types")
    private String allowedInstanceTypes;

    @JsonProperty("disallowed_instance_types")
    private String disallowedInstanceTypes;

    @JsonProperty("instance_termination_method")
    private String instanceTerminationMethod;
}
