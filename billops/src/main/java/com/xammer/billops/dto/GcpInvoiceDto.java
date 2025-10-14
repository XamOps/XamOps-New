package com.xammer.billops.dto;

import java.util.List;
import java.util.Map;

public class GcpInvoiceDto {

    private double totalCost;
    private List<Map<String, Object>> costByService;
    private List<Map<String, Object>> costByProject;

    // Getters and Setters

    public double getTotalCost() {
        return totalCost;
    }

    public void setTotalCost(double totalCost) {
        this.totalCost = totalCost;
    }

    public List<Map<String, Object>> getCostByService() {
        return costByService;
    }

    public void setCostByService(List<Map<String, Object>> costByService) {
        this.costByService = costByService;
    }

    public List<Map<String, Object>> getCostByProject() {
        return costByProject;
    }

    public void setCostByProject(List<Map<String, Object>> costByProject) {
        this.costByProject = costByProject;
    }
}