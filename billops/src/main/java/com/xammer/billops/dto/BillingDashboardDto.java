package com.xammer.billops.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class BillingDashboardDto {

    private List<CostHistory> costHistory;
    private List<ServiceBreakdown> serviceBreakdown;

    public BillingDashboardDto() {}

    @Getter
    @Setter
    public static class CostHistory {
        private String date;
        private double cost;
        
        public CostHistory() {}

        public CostHistory(String date, double cost) {
            this.date = date;
            this.cost = cost;
        }
    }

    @Getter
    @Setter
    public static class ServiceBreakdown {
        private String name;
        private double cost;

        public ServiceBreakdown() {} // Add this no-argument constructor

        public ServiceBreakdown(String name, double cost) {
            this.name = name;
            this.cost = cost;
        }
    }
}