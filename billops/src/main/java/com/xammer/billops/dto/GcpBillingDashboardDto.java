package com.xammer.billops.dto;

import java.math.BigDecimal;
import java.util.List;

public class GcpBillingDashboardDto {

    private BigDecimal totalCost;
    private BigDecimal costThisMonth;
    private BigDecimal costLastMonth;
    private List<CostHistory> costHistory;
    private List<ServiceBreakdown> serviceBreakdown;

    // Constructors
    public GcpBillingDashboardDto() {}

    public GcpBillingDashboardDto(BigDecimal totalCost, List<CostHistory> costHistory, List<ServiceBreakdown> serviceBreakdown) {
        this.totalCost = totalCost;
        this.costHistory = costHistory;
        this.serviceBreakdown = serviceBreakdown;
    }

    // Getters and Setters
    public BigDecimal getTotalCost() {
        return totalCost;
    }

    public void setTotalCost(BigDecimal totalCost) {
        this.totalCost = totalCost;
    }

    public BigDecimal getCostThisMonth() {
        return costThisMonth;
    }

    public void setCostThisMonth(BigDecimal costThisMonth) {
        this.costThisMonth = costThisMonth;
    }

    public BigDecimal getCostLastMonth() {
        return costLastMonth;
    }

    public void setCostLastMonth(BigDecimal costLastMonth) {
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

    // Inner classes for structured data
    public static class CostHistory {
        private String date;
        private BigDecimal amount;
        private boolean isAnomaly;

        // *** ADDED NO-ARGUMENT CONSTRUCTOR ***
        public CostHistory() {}
        // *** END OF ADDITION ***

        public CostHistory(String date, BigDecimal amount, boolean isAnomaly) {
            this.date = date;
            this.amount = amount;
            this.isAnomaly = isAnomaly;
        }

        // Getters and Setters
        public String getDate() {
            return date;
        }

        public void setDate(String date) {
            this.date = date;
        }

        public BigDecimal getAmount() {
            return amount;
        }

        public void setAmount(BigDecimal amount) {
            this.amount = amount;
        }

        public boolean isAnomaly() {
            return isAnomaly;
        }

        public void setAnomaly(boolean anomaly) {
            isAnomaly = anomaly;
        }
    }

    public static class ServiceBreakdown {
        private String serviceName;
        private BigDecimal amount;

        // *** ADDED NO-ARGUMENT CONSTRUCTOR ***
        public ServiceBreakdown() {}
        // *** END OF ADDITION ***

        public ServiceBreakdown(String serviceName, BigDecimal amount) {
            this.serviceName = serviceName;
            this.amount = amount;
        }

        // Getters and Setters
        public String getServiceName() {
            return serviceName;
        }

        public void setServiceName(String serviceName) {
            this.serviceName = serviceName;
        }

        public BigDecimal getAmount() {
            return amount;
        }

        public void setAmount(BigDecimal amount) {
            this.amount = amount;
        }
    }
}