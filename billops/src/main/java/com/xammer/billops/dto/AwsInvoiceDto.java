package com.xammer.billops.dto;

import lombok.Data;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Data
public class AwsInvoiceDto {
    // Header Info
    private String accountId;
    private String accountName;
    private LocalDate invoiceDate;
    private String billingPeriod;

    // Summary Section
    private double estimatedGrandTotal;
    private Map<String, Double> totalsByServiceProvider;
    private InvoiceSummaryItem highestServiceSpend;
    private InvoiceSummaryItem highestRegionSpend;

    // Main Charges Section
    private List<ChargesByServiceDto> chargesByService;

    // Footer/Totals
    private double totalPreTax;
    private double totalTax; // Placeholder
    private double totalPostTax;
}