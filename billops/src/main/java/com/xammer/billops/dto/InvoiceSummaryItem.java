package com.xammer.billops.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class InvoiceSummaryItem {
    private String name;
    private double amount;
    private String trend; // e.g., "↑144.3%"
}