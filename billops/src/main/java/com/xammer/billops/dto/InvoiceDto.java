package com.xammer.billops.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import com.xammer.cloud.domain.Discount;
import com.xammer.cloud.domain.Invoice;
import com.xammer.cloud.domain.InvoiceLineItem;

public class InvoiceDto {
    
    private Long id;
    private String invoiceNumber;
    private LocalDate invoiceDate;
    private String billingPeriod;
    private String status;
    private Long clientId;
    private String clientName;
    private Long cloudAccountId;
    private String accountName;
    private String awsAccountId;
    private String gcpProjectId;
    private BigDecimal preDiscountTotal;
    private BigDecimal discountAmount;
    private BigDecimal taxAmount; // New Field
    private BigDecimal amount;
    private List<LineItemDto> lineItems;
    private List<DiscountDto> discounts;
    
    // Static factory method
    public static InvoiceDto fromEntity(Invoice invoice) {
        InvoiceDto dto = new InvoiceDto();
        dto.setId(invoice.getId());
        dto.setInvoiceNumber(invoice.getInvoiceNumber());
        dto.setInvoiceDate(invoice.getInvoiceDate());
        dto.setBillingPeriod(invoice.getBillingPeriod());
        dto.setStatus(invoice.getStatus() != null ? invoice.getStatus().name() : "DRAFT");
        
        // Client info
        if (invoice.getClient() != null) {
            dto.setClientId(invoice.getClient().getId());
            dto.setClientName(invoice.getClient().getName());
        }
        
        // Cloud account info
        try {
            if (invoice.getCloudAccount() != null) {
                dto.setCloudAccountId(invoice.getCloudAccount().getId());
                dto.setAccountName(invoice.getCloudAccount().getAccountName());
                dto.setAwsAccountId(invoice.getCloudAccount().getAwsAccountId());
                dto.setGcpProjectId(invoice.getCloudAccount().getGcpProjectId());
            }
        } catch (javax.persistence.EntityNotFoundException e) {
            dto.setAccountName("Unknown/Deleted Account");
            dto.setAwsAccountId("N/A");
            dto.setGcpProjectId("N/A");
        }
        
        dto.setPreDiscountTotal(invoice.getPreDiscountTotal());
        dto.setDiscountAmount(invoice.getDiscountAmount());
        dto.setTaxAmount(invoice.getTaxAmount()); // Map tax
        dto.setAmount(invoice.getAmount());
        
        // Convert line items (only non-hidden items)
        List<LineItemDto> items = Optional.ofNullable(invoice.getLineItems())
            .orElse(Collections.emptyList())
            .stream()
            .filter(item -> !item.isHidden())
            .map(LineItemDto::fromEntity)
            .collect(Collectors.toList());
        dto.setLineItems(items);
        
        // Convert discounts
        List<DiscountDto> discountDtos = Optional.ofNullable(invoice.getDiscounts())
            .orElse(Collections.emptyList())
            .stream()
            .map(DiscountDto::fromEntity)
            .collect(Collectors.toList());
        dto.setDiscounts(discountDtos);
        
        return dto;
    }
    
    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getInvoiceNumber() { return invoiceNumber; }
    public void setInvoiceNumber(String invoiceNumber) { this.invoiceNumber = invoiceNumber; }
    public LocalDate getInvoiceDate() { return invoiceDate; }
    public void setInvoiceDate(LocalDate invoiceDate) { this.invoiceDate = invoiceDate; }
    public String getBillingPeriod() { return billingPeriod; }
    public void setBillingPeriod(String billingPeriod) { this.billingPeriod = billingPeriod; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getClientId() { return clientId; }
    public void setClientId(Long clientId) { this.clientId = clientId; }
    public String getClientName() { return clientName; }
    public void setClientName(String clientName) { this.clientName = clientName; }
    public Long getCloudAccountId() { return cloudAccountId; }
    public void setCloudAccountId(Long cloudAccountId) { this.cloudAccountId = cloudAccountId; }
    public String getAccountName() { return accountName; }
    public void setAccountName(String accountName) { this.accountName = accountName; }
    public String getAwsAccountId() { return awsAccountId; }
    public void setAwsAccountId(String awsAccountId) { this.awsAccountId = awsAccountId; }
    public String getGcpProjectId() { return gcpProjectId; }
    public void setGcpProjectId(String gcpProjectId) { this.gcpProjectId = gcpProjectId; }
    public BigDecimal getPreDiscountTotal() { return preDiscountTotal; }
    public void setPreDiscountTotal(BigDecimal preDiscountTotal) { this.preDiscountTotal = preDiscountTotal; }
    public BigDecimal getDiscountAmount() { return discountAmount; }
    public void setDiscountAmount(BigDecimal discountAmount) { this.discountAmount = discountAmount; }
    
    public BigDecimal getTaxAmount() { return taxAmount; }
    public void setTaxAmount(BigDecimal taxAmount) { this.taxAmount = taxAmount; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    
    public List<LineItemDto> getLineItems() { return lineItems; }
    public void setLineItems(List<LineItemDto> lineItems) { this.lineItems = lineItems; }
    public List<DiscountDto> getDiscounts() { return discounts; }
    public void setDiscounts(List<DiscountDto> discounts) { this.discounts = discounts; }
    
    // Inner class for Line Items
    public static class LineItemDto {
        private Long id;
        private String serviceName;
        private String regionName;
        private String resourceName;
        private String usageQuantity;
        private String unit;
        private BigDecimal cost;
        private boolean hidden;
        private BigDecimal quantity;
        private BigDecimal unitRate;
        
        public static LineItemDto fromEntity(InvoiceLineItem item) {
            LineItemDto dto = new LineItemDto();
            dto.setId(item.getId());
            dto.setServiceName(item.getServiceName());
            dto.setRegionName(item.getRegionName());
            dto.setResourceName(item.getResourceName());
            dto.setUsageQuantity(item.getUsageQuantity());
            dto.setUnit(item.getUnit());
            dto.setCost(item.getCost());
            dto.setHidden(item.isHidden());
            
            try {
                String[] parts = item.getUsageQuantity().split(" ");
                dto.setQuantity(new BigDecimal(parts[0].replace(",", "")));
                if (dto.getQuantity().compareTo(BigDecimal.ZERO) > 0) {
                    dto.setUnitRate(item.getCost().divide(dto.getQuantity(), 10, RoundingMode.HALF_UP));
                } else {
                    dto.setUnitRate(BigDecimal.ZERO);
                }
            } catch (Exception e) {
                dto.setQuantity(BigDecimal.ONE);
                dto.setUnitRate(item.getCost());
            }
            return dto;
        }
        // Getters/Setters omitted for brevity, same as before...
        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getServiceName() { return serviceName; }
        public void setServiceName(String serviceName) { this.serviceName = serviceName; }
        public String getRegionName() { return regionName; }
        public void setRegionName(String regionName) { this.regionName = regionName; }
        public String getResourceName() { return resourceName; }
        public void setResourceName(String resourceName) { this.resourceName = resourceName; }
        public String getUsageQuantity() { return usageQuantity; }
        public void setUsageQuantity(String usageQuantity) { this.usageQuantity = usageQuantity; }
        public String getUnit() { return unit; }
        public void setUnit(String unit) { this.unit = unit; }
        public BigDecimal getCost() { return cost; }
        public void setCost(BigDecimal cost) { this.cost = cost; }
        public boolean isHidden() { return hidden; }
        public void setHidden(boolean hidden) { this.hidden = hidden; }
        public BigDecimal getQuantity() { return quantity; }
        public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }
        public BigDecimal getUnitRate() { return unitRate; }
        public void setUnitRate(BigDecimal unitRate) { this.unitRate = unitRate; }
    }
    
    // Inner class for Discounts
    public static class DiscountDto {
        private Long id;
        private String serviceName;
        private BigDecimal percentage;
        private String description;
        
        public static DiscountDto fromEntity(Discount discount) {
            DiscountDto dto = new DiscountDto();
            dto.setId(discount.getId());
            dto.setServiceName(discount.getServiceName());
            dto.setPercentage(discount.getPercentage());
            dto.setDescription(discount.getDescription());
            return dto;
        }
        // Getters/Setters
        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getServiceName() { return serviceName; }
        public void setServiceName(String serviceName) { this.serviceName = serviceName; }
        public BigDecimal getPercentage() { return percentage; }
        public void setPercentage(BigDecimal percentage) { this.percentage = percentage; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
    }
}