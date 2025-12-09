package com.xammer.cloud.dto.autospotting;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ASGConfigUpdate {
    private Boolean enabled;

    @JsonProperty("on_demand_number")
    private Integer onDemandNumber;

    @JsonProperty("on_demand_percentage")
    private Double onDemandPercentage;

    @JsonProperty("min_on_demand_number")
    private Integer minOnDemandNumber;

    @JsonProperty("min_on_demand_percentage")
    private Double minOnDemandPercentage;

    @JsonProperty("spot_price_buffer_percentage")
    private Double spotPriceBufferPercentage;

    @JsonProperty("bidding_policy")
    private String biddingPolicy;

    @JsonProperty("allowed_instance_types")
    private List<String> allowedInstanceTypes;

    @JsonProperty("disallowed_instance_types")
    private List<String> disallowedInstanceTypes;

    @JsonProperty("spot_allocation_strategy")
    private String spotAllocationStrategy;
}
