package com.xammer.billops.dto;

import java.math.BigDecimal;

public class GcpResourceCostDto {

    private String resourceName;
    private BigDecimal amount;

    public GcpResourceCostDto(String resourceName, BigDecimal amount) {
        this.resourceName = resourceName;
        this.amount = amount;
    }

    // Getters and Setters
    public String getResourceName() {
        return resourceName;
    }

    public void setResourceName(String resourceName) {
        this.resourceName = resourceName;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }
}