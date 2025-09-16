package com.xammer.billops.dto;

import java.util.List;

public class ChargesByServiceDto {
    private String serviceName;
    private double totalAmount;
    private List<ChargeLineItemDto> lineItems;

    // Manually Added
    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }
    public double getTotalAmount() { return totalAmount; }
    public void setTotalAmount(double totalAmount) { this.totalAmount = totalAmount; }
    public List<ChargeLineItemDto> getLineItems() { return lineItems; }
    public void setLineItems(List<ChargeLineItemDto> lineItems) { this.lineItems = lineItems; }
}