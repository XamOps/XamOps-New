package com.xammer.billops.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChargeLineItemDto {
    private String description;
    private String usageQuantity;
    private String amount; // Using String to preserve formatting like "(USD 0.00)"
    private int indentationLevel; // To handle the nested descriptions
}