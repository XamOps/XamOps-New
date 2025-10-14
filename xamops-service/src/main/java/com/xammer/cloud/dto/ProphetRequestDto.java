package com.xammer.cloud.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProphetRequestDto {

    @JsonProperty("data")
    private List<Map<String, Object>> data; // Historical data points with 'ds' and 'y'

    @JsonProperty("periods")
    private int periods; // Number of future periods to forecast

    @JsonProperty("weekly_seasonality")
    private boolean weeklySeasonality = true;

    @JsonProperty("yearly_seasonality")
    private boolean yearlySeasonality = false;
}
