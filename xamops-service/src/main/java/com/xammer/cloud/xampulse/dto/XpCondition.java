package com.xammer.cloud.xampulse.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record XpCondition(@NotBlank String type, String metric, List<XpMetricFilter> filters, String query, String agg, @NotBlank String window, String op, Double value, @JsonProperty("for") String forDuration, String noData, Integer forecastDays) {}
