package com.xammer.cloud.xampulse.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record XpCostEstimate(double s3Usd, double kmsUsd, List<String> notes) {}
