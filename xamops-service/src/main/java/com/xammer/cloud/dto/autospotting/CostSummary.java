package com.xammer.cloud.dto.autospotting;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CostSummary {
    @JsonProperty("total_current_hourly_cost")
    private Double totalCurrentHourlyCost;

    @JsonProperty("total_ondemand_hourly_cost")
    private Double totalOndemandHourlyCost;

    @JsonProperty("total_actual_savings")
    private Double totalActualSavings;

    @JsonProperty("total_potential_savings")
    private Double totalPotentialSavings;

    @JsonProperty("total_asg_count")
    private Integer totalAsgCount;

    @JsonProperty("autospotting_enabled_count")
    private Integer autospottingEnabledCount;
}
