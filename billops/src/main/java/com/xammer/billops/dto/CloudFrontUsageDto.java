package com.xammer.billops.dto;

public class CloudFrontUsageDto {
    private String region;
    private String usageType;
    private double quantity;
    private String unit;
    private double cost;

    public CloudFrontUsageDto(String region, String usageType, double quantity, String unit, double cost) {
        this.region = region;
        this.usageType = usageType;
        this.quantity = quantity;
        this.unit = unit;
        this.cost = cost;
    }

    // Getters and Setters
    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getUsageType() {
        return usageType;
    }

    public void setUsageType(String usageType) {
        this.usageType = usageType;
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

    public double getCost() {
        return cost;
    }

    public void setCost(double cost) {
        this.cost = cost;
    }
}
