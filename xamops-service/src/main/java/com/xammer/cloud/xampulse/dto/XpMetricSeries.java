package com.xammer.cloud.xampulse.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record XpMetricSeries(String metric, Map<String,String> labels, List<Object[]> points, List<double[]> band, List<Object[]> compare) {}
