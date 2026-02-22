package com.xammer.billops.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class InvoiceSummaryItem {
    private String label;
    private double amount;
    private String details;

    // public InvoiceSummaryItem() {
    // }

    // public InvoiceSummaryItem(String label, double amount, String details) {
    // this.label = label;
    // this.amount = amount;
    // this.details = details;
    // }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public double getAmount() {
        return amount;
    }

    public void setAmount(double amount) {
        this.amount = amount;
    }

    public String getDetails() {
        return details;
    }

    public void setDetails(String details) {
        this.details = details;
    }
}