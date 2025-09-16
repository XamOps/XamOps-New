package com.xammer.billops.dto;

import com.opencsv.bean.CsvBindByName;

public class CostAndUsageRecord {

    @CsvBindByName(column = "lineItem/UsageStartDate")
    private String usageStartDate;
    @CsvBindByName(column = "lineItem/ProductCode")
    private String productCode;
    @CsvBindByName(column = "product/ProductName")
    private String productName;
    @CsvBindByName(column = "product/region")
    private String region;
    @CsvBindByName(column = "lineItem/UsageType")
    private String usageType;
    @CsvBindByName(column = "lineItem/UsageAmount")
    private String usageAmount;
    @CsvBindByName(column = "lineItem/UnblendedCost")
    private String unblendedCost;

    // Manually Added
    public String getUsageStartDate() { return usageStartDate; }
    public void setUsageStartDate(String usageStartDate) { this.usageStartDate = usageStartDate; }
    public String getProductCode() { return productCode; }
    public void setProductCode(String productCode) { this.productCode = productCode; }
    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public String getUsageType() { return usageType; }
    public void setUsageType(String usageType) { this.usageType = usageType; }
    public String getUsageAmount() { return usageAmount; }
    public void setUsageAmount(String usageAmount) { this.usageAmount = usageAmount; }
    public String getUnblendedCost() { return unblendedCost; }
    public void setUnblendedCost(String unblendedCost) { this.unblendedCost = unblendedCost; }
}