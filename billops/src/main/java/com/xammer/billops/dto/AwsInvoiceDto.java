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

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }
    public String getAccountName() { return accountName; }
    public void setAccountName(String accountName) { this.accountName = accountName; }
    public LocalDate getInvoiceDate() { return invoiceDate; }
    public void setInvoiceDate(LocalDate invoiceDate) { this.invoiceDate = invoiceDate; }
    public String getBillingPeriod() { return billingPeriod; }
    public void setBillingPeriod(String billingPeriod) { this.billingPeriod = billingPeriod; }
    public double getEstimatedGrandTotal() { return estimatedGrandTotal; }
    public void setEstimatedGrandTotal(double estimatedGrandTotal) { this.estimatedGrandTotal = estimatedGrandTotal; }
    public Map<String, Double> getTotalsByServiceProvider() { return totalsByServiceProvider; }
    public void setTotalsByServiceProvider(Map<String, Double> totalsByServiceProvider) { this.totalsByServiceProvider = totalsByServiceProvider; }
    public InvoiceSummaryItem getHighestServiceSpend() { return highestServiceSpend; }
    public void setHighestServiceSpend(InvoiceSummaryItem highestServiceSpend) { this.highestServiceSpend = highestServiceSpend; }
    public InvoiceSummaryItem getHighestRegionSpend() { return highestRegionSpend; }
    public void setHighestRegionSpend(InvoiceSummaryItem highestRegionSpend) { this.highestRegionSpend = highestRegionSpend; }
    public List<ChargesByServiceDto> getChargesByService() { return chargesByService; }
    public void setChargesByService(List<ChargesByServiceDto> chargesByService) { this.chargesByService = chargesByService; }
    public double getTotalPreTax() { return totalPreTax; }
    public void setTotalPreTax(double totalPreTax) { this.totalPreTax = totalPreTax; }
    public double getTotalTax() { return totalTax; }
    public void setTotalTax(double totalTax) { this.totalTax = totalTax; }
    public double getTotalPostTax() { return totalPostTax; }
    public void setTotalPostTax(double totalPostTax) { this.totalPostTax = totalPostTax; }
}