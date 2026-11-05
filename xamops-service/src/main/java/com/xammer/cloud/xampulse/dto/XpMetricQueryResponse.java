package com.xammer.cloud.xampulse.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record XpMetricQueryResponse(int stepSec, List<XpMetricSeries> series, long tookMs) {}
