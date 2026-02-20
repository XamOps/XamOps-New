package com.xammer.billops.dto;

import lombok.Data;
import java.util.List;

@Data
public class BillingDashboardDto {
    private double currentMonthCost;
    private double previousMonthCost;
    private double forecastCost;
    private double variance;
    private long lastUpdated;
    private List<CostHistory> costHistory;
    private List<ServiceBreakdown> serviceBreakdown;

    public double getCurrentMonthCost() { return currentMonthCost; }
    public void setCurrentMonthCost(double currentMonthCost) { this.currentMonthCost = currentMonthCost; }
    public double getPreviousMonthCost() { return previousMonthCost; }
    public void setPreviousMonthCost(double previousMonthCost) { this.previousMonthCost = previousMonthCost; }
    public double getForecastCost() { return forecastCost; }
    public void setForecastCost(double forecastCost) { this.forecastCost = forecastCost; }
    public double getVariance() { return variance; }
    public void setVariance(double variance) { this.variance = variance; }
    public long getLastUpdated() { return lastUpdated; }
    public void setLastUpdated(long lastUpdated) { this.lastUpdated = lastUpdated; }
    public List<CostHistory> getCostHistory() { return costHistory; }
    public void setCostHistory(List<CostHistory> costHistory) { this.costHistory = costHistory; }
    public List<ServiceBreakdown> getServiceBreakdown() { return serviceBreakdown; }
    public void setServiceBreakdown(List<ServiceBreakdown> serviceBreakdown) { this.serviceBreakdown = serviceBreakdown; }

    @Data
    public static class CostHistory {
        private String month;
        private String date;
        private double cost;

        public CostHistory() {
        }

        public CostHistory(String date, Double cost) {
            this.date = date;
            this.month = date;
            this.cost = cost;
        }

        public String getMonth() { return month; }
        public void setMonth(String month) { this.month = month; }
        public String getDate() { return date; }
        public void setDate(String date) { this.date = date; }
        public double getCost() { return cost; }
        public void setCost(double cost) { this.cost = cost; }
    }

    @Data
    public static class ServiceBreakdown {
        private String service;
        private String name;
        private double cost;
        private double percentage;

        public ServiceBreakdown() {
        }

        public ServiceBreakdown(String name, Double cost) {
            this.name = name;
            this.service = name;
            this.cost = cost;
        }

        public String getService() { return service; }
        public void setService(String service) { this.service = service; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public double getCost() { return cost; }
        public void setCost(double cost) { this.cost = cost; }
        public double getPercentage() { return percentage; }
        public void setPercentage(double percentage) { this.percentage = percentage; }
    }
}