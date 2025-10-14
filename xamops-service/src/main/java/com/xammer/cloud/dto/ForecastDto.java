package com.xammer.cloud.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ForecastDto {

    @JsonProperty("ds")
    private String ds; // Date string, e.g., "2025-10-27"

    @JsonProperty("yhat")
    private double yhat; // Forecasted value

    @JsonProperty("yhat_lower")
    private double yhatLower; // Lower bound of the forecast uncertainty interval

    @JsonProperty("yhat_upper")
    private double yhatUpper; // Upper bound of the forecast uncertainty interval
}
