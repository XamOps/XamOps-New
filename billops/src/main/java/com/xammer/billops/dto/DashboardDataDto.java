package com.xammer.billops.dto;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

@Getter
@Setter
@Builder
public class DashboardDataDto {
    // KPIs
    private double monthToDateSpend;
    private double forecastedSpend;
    private double lastMonthSpend;

    // Chart Data
    private List<Map<String, Object>> costHistory;
    private List<Map<String, Object>> costByService;
    private List<Map<String, Object>> costByRegion;

    // Table Data
    private List<Map<String, Object>> costBreakdown;
}