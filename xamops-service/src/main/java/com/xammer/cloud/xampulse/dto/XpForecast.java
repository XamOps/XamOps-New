package com.xammer.cloud.xampulse.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record XpForecast(Long accountId, String instanceId, String resource, String metric, Double daysToFull, List<Double> intervalDays, double r2, String message) {}
