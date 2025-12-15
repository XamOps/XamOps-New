package com.xammer.billops.dto.azure;

import java.util.ArrayList;
import java.util.List;

public class AzureBillingDashboardDto {
    private Double totalCost;
    private Double costLastMonth; // Optional: for comparison
    private List<CostHistory> costHistory;
    private List<ServiceBreakdown> serviceBreakdown;

    public AzureBillingDashboardDto() {
        this.totalCost = 0.0;
        this.costLastMonth = 0.0;
        this.costHistory = new ArrayList<>();
        this.serviceBreakdown = new ArrayList<>();
    }

    public Double getTotalCost() {
        return totalCost;
    }

    public void setTotalCost(Double totalCost) {
        this.totalCost = totalCost;
    }

    public Double getCostLastMonth() {
        return costLastMonth;
    }

    public void setCostLastMonth(Double costLastMonth) {
        this.costLastMonth = costLastMonth;
    }

    public List<CostHistory> getCostHistory() {
        return costHistory;
    }

    public void setCostHistory(List<CostHistory> costHistory) {
        this.costHistory = costHistory;
    }

    public List<ServiceBreakdown> getServiceBreakdown() {
        return serviceBreakdown;
    }

    public void setServiceBreakdown(List<ServiceBreakdown> serviceBreakdown) {
        this.serviceBreakdown = serviceBreakdown;
    }

    // Nested Static Classes

    public static class CostHistory {
        private String date;
        private Double cost;

        public CostHistory(String date, Double cost) {
            this.date = date;
            this.cost = cost;
        }

        public String getDate() {
            return date;
        }

        public void setDate(String date) {
            this.date = date;
        }

        public Double getCost() {
            return cost;
        }

        public void setCost(Double cost) {
            this.cost = cost;
        }
    }

    public static class ServiceBreakdown {
        private String serviceName;
        private Double amount;

        public ServiceBreakdown(String serviceName, Double amount) {
            this.serviceName = serviceName;
            this.amount = amount;
        }

        public String getServiceName() {
            return serviceName;
        }

        public void setServiceName(String serviceName) {
            this.serviceName = serviceName;
        }

        public Double getAmount() {
            return amount;
        }

        public void setAmount(Double amount) {
            this.amount = amount;
        }
    }
}