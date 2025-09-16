package com.xammer.billops.dto;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class DiscountRequestDto {
    private String serviceName;
    private BigDecimal percentage;
}