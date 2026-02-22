package com.xammer.billops.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ResourceCostDto {
    private String resourceId;
    private String resourceName;
    private double cost;
    private double quantity;
    private String unit;
    private String resourceType;
    private double usage;

    // public ResourceCostDto() {
    // }

    public ResourceCostDto(String resourceId, String resourceType, double usage, double cost, String resourceName) {
        this.resourceId = resourceId;
        this.resourceType = resourceType;
        this.usage = usage;
        this.cost = cost;
        this.resourceName = resourceName;
    }

    public String getResourceName() {
        return resourceName;
    }

    public void setResourceName(String resourceName) {
        this.resourceName = resourceName;
    }

    public String getResourceId() {
        return resourceId;
    }

    public void setResourceId(String resourceId) {
        this.resourceId = resourceId;
    }

    public double getCost() {
        return cost;
    }

    public void setCost(double cost) {
        this.cost = cost;
    }

    public double getQuantity() {
        return quantity;
    }

    public void setQuantity(double quantity) {
        this.quantity = quantity;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    public String getResourceType() {
        return resourceType;
    }

    public void setResourceType(String resourceType) {
        this.resourceType = resourceType;
    }

    public double getUsage() {
        return usage;
    }

    public void setUsage(double usage) {
        this.usage = usage;
    }
}