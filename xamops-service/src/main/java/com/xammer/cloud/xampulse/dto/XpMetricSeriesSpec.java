package com.xammer.cloud.xampulse.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record XpMetricSeriesSpec(@NotBlank String metric, @NotBlank String agg, List<String> groupBy, List<XpMetricFilter> filters, Integer stepSec) {}
