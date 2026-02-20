package com.xammer.billops.dto;

import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class DashboardDataDto {
    // KPIs
    private double monthToDateSpend;
    private double forecastedSpend;
    private double lastMonthSpend;
    private double monthlySpend;
    private double previousMonthSpend;
    private double costTrend;

    // Chart Data
    private List<Map<String, Object>> costHistory;
    private List<Map<String, Object>> costByService;
    private List<Map<String, Object>> costByRegion;

    // Table Data
    private List<Map<String, Object>> costBreakdown;

    public double getMonthToDateSpend() { return monthToDateSpend; }
    public void setMonthToDateSpend(double monthToDateSpend) { this.monthToDateSpend = monthToDateSpend; }
    public double getForecastedSpend() { return forecastedSpend; }
    public void setForecastedSpend(double forecastedSpend) { this.forecastedSpend = forecastedSpend; }
    public double getLastMonthSpend() { return lastMonthSpend; }
    public void setLastMonthSpend(double lastMonthSpend) { this.lastMonthSpend = lastMonthSpend; }
    public double getMonthlySpend() { return monthlySpend; }
    public void setMonthlySpend(double monthlySpend) { this.monthlySpend = monthlySpend; }
    public double getPreviousMonthSpend() { return previousMonthSpend; }
    public void setPreviousMonthSpend(double previousMonthSpend) { this.previousMonthSpend = previousMonthSpend; }
    public double getCostTrend() { return costTrend; }
    public void setCostTrend(double costTrend) { this.costTrend = costTrend; }
    public List<Map<String, Object>> getCostHistory() { return costHistory; }
    public void setCostHistory(List<Map<String, Object>> costHistory) { this.costHistory = costHistory; }
    public List<Map<String, Object>> getCostByService() { return costByService; }
    public void setCostByService(List<Map<String, Object>> costByService) { this.costByService = costByService; }
    public List<Map<String, Object>> getCostByRegion() { return costByRegion; }
    public void setCostByRegion(List<Map<String, Object>> costByRegion) { this.costByRegion = costByRegion; }
    public List<Map<String, Object>> getCostBreakdown() { return costBreakdown; }
    public void setCostBreakdown(List<Map<String, Object>> costBreakdown) { this.costBreakdown = costBreakdown; }
}