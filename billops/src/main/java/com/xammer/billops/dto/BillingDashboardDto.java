package com.xammer.billops.dto;

import lombok.Getter;

import java.util.List;

@Getter
public class BillingDashboardDto {

    // Getters and Setters
    private List<CostHistory> costHistory;
    private List<ServiceBreakdown> serviceBreakdown;

    // Default constructor
    public BillingDashboardDto() {}

    public void setCostHistory(List<CostHistory> costHistory) { this.costHistory = costHistory; }

    public void setServiceBreakdown(List<ServiceBreakdown> serviceBreakdown) { this.serviceBreakdown = serviceBreakdown; }

    // Inner classes for data structure
    public static class CostHistory {
        private String date;
        private double cost;

        public CostHistory() {}

        public CostHistory(String date, double cost) {
            this.date = date;
            this.cost = cost;
        }

        public String getDate() { return date; }
        public void setDate(String date) { this.date = date; }
        public double getCost() { return cost; }
        public void setCost(double cost) { this.cost = cost; }
    }

    public static class ServiceBreakdown {
        private String serviceName;

        // Default constructor
        public ServiceBreakdown() {}

        // Constructor with arguments
        public ServiceBreakdown(String serviceName) {
            this.serviceName = serviceName;
        }

        // Getter for serviceName
        public String getServiceName() {
            return serviceName;
        }

        // Setter for serviceName
        public void setServiceName(String serviceName) {
            this.serviceName = serviceName;
        }
    }
}