package com.xammer.cloud.xampulse.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record XpUsage(String range, List<XpUsageDay> byDay, List<XpUsageSource> bySource, long hotBytes, long archiveBytes, Double projectedMonthlyCustomerAwsUsd, Double quotaGbPerDay) {}
