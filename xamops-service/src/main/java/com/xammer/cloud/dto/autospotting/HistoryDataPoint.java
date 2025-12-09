package com.xammer.cloud.dto.autospotting;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class HistoryDataPoint {
    private Instant timestamp;

    @JsonProperty("total_actual_savings")
    private Double totalActualSavings;

    @JsonProperty("total_potential_savings")
    private Double totalPotentialSavings;

    @JsonProperty("total_current_cost")
    private Double totalCurrentCost;
}
