package com.xammer.cloud.dto.autospotting;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
public class ASGCostData {
    
    @JsonProperty("account_id")
    private String accountId;
    
    @JsonProperty("asg_name")
    private String asgName;
    
    @JsonProperty("region")
    private String region;
    
    @JsonProperty("autospotting_enabled")
    private Boolean autospottingEnabled;
    
    @JsonProperty("instance_count")
    private Integer instanceCount;
    
    @JsonProperty("ondemand_instance_count")
    private Integer ondemandInstanceCount;
    
    @JsonProperty("spot_instance_count")
    private Integer spotInstanceCount;
    
    @JsonProperty("current_hourly_cost")
    private Double currentHourlyCost;
    
    @JsonProperty("ondemand_hourly_cost")
    private Double ondemandHourlyCost;
    
    @JsonProperty("spot_hourly_cost")
    private Double spotHourlyCost;
    
    @JsonProperty("actual_hourly_savings")
    private Double actualHourlySavings;
    
    @JsonProperty("potential_hourly_savings")
    private Double potentialHourlySavings;
    
    @JsonProperty("instance_types")
    private List<String> instanceTypes;
}
