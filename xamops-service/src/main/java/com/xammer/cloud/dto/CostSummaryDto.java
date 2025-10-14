package com.xammer.cloud.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CostSummaryDto {

    @JsonProperty("accountId")
    private String accountId;

    @JsonProperty("serviceBreakdown")
    private List<CostDto> serviceBreakdown;

    @JsonProperty("regionBreakdown")
    private List<CostDto> regionBreakdown;

    @JsonProperty("historicalTrend")
    private HistoricalCostDto historicalTrend;

    @JsonProperty("forecast")
    private CostForecastDto forecast;

    @JsonProperty("totalCurrentCost")
    private Double totalCurrentCost;

    @JsonProperty("totalForecastedCost")
    private Double totalForecastedCost;

    @JsonProperty("costDelta")
    private Double costDelta; // Difference between forecast and current

    @JsonProperty("days")
    private int days;
}
