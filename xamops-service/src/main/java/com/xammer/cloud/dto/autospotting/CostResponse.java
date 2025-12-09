package com.xammer.cloud.dto.autospotting;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
public class CostResponse {

    @JsonProperty("asgs")
    private List<ASGCostData> asgs;

    @JsonProperty("summary")
    private CostSummary summary;

    @JsonProperty("timestamp")
    private String timestamp;

    @Data
    public static class CostSummary {
        @JsonProperty("total_asg_count")
        private Integer totalAsgCount;

        @JsonProperty("autospotting_enabled_count")
        private Integer autospottingEnabledCount;

        @JsonProperty("total_current_hourly_cost")
        private Double totalCurrentHourlyCost;

        @JsonProperty("total_ondemand_hourly_cost")
        private Double totalOndemandHourlyCost;

        @JsonProperty("total_actual_savings")
        private Double totalActualSavings;

        @JsonProperty("total_potential_savings")
        private Double totalPotentialSavings;
    }
}
