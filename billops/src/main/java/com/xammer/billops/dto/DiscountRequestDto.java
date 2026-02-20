package com.xammer.billops.dto;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class DiscountRequestDto {
    private String serviceName;
    private BigDecimal percentage;

    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }
    public BigDecimal getPercentage() { return percentage; }
    public void setPercentage(BigDecimal percentage) { this.percentage = percentage; }
}