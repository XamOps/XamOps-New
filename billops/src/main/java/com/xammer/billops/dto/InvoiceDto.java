package com.xammer.billops.dto;

import com.xammer.billops.domain.Invoice;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

@Data
public class InvoiceDto {

    private Long id;
    private String invoiceNumber;
    private LocalDate invoiceDate;
    private String billingPeriod;
    private Invoice.InvoiceStatus status;
    private BigDecimal preDiscountTotal;
    private BigDecimal discountAmount;
    private BigDecimal amount;

    private String accountName;
    private String awsAccountId;
    private List<LineItemDto> lineItems;
    private List<DiscountDto> discounts;

    @Data
    public static class LineItemDto {
        private String serviceName;
        private String regionName;
        private String resourceName;
        private String usageQuantity;
        private String unit;
        private BigDecimal cost;
        private boolean hidden;
    }

    @Data
    public static class DiscountDto {
        private Long id;
        private String serviceName;
        private BigDecimal percentage;
        private String description;
    }

    public static InvoiceDto fromEntity(Invoice invoice) {
        InvoiceDto dto = new InvoiceDto();
        dto.setId(invoice.getId());
        dto.setInvoiceNumber(invoice.getInvoiceNumber());
        dto.setInvoiceDate(invoice.getInvoiceDate());
        dto.setBillingPeriod(invoice.getBillingPeriod());
        dto.setStatus(invoice.getStatus());
        dto.setPreDiscountTotal(invoice.getPreDiscountTotal());
        dto.setDiscountAmount(invoice.getDiscountAmount());
        dto.setAmount(invoice.getAmount());

        dto.setAccountName(invoice.getCloudAccount().getAccountName());
        dto.setAwsAccountId(invoice.getCloudAccount().getAwsAccountId());

        if (invoice.getLineItems() != null) {
            dto.setLineItems(invoice.getLineItems().stream()
                // --- START: THIS IS THE FIX ---
                // This filter removes any hidden items before sending the data to the user's invoice page.
                .filter(item -> !item.isHidden())
                // --- END: THIS IS THE FIX ---
                .map(item -> {
                    LineItemDto itemDto = new LineItemDto();
                    itemDto.setServiceName(item.getServiceName());
                    itemDto.setRegionName(item.getRegionName());
                    itemDto.setResourceName(item.getResourceName());
                    itemDto.setUsageQuantity(item.getUsageQuantity());
                    itemDto.setUnit(item.getUnit());
                    itemDto.setCost(item.getCost());
                    itemDto.setHidden(item.isHidden()); // This is still useful for the admin view
                    return itemDto;
                }).collect(Collectors.toList()));
        }

        if (invoice.getDiscounts() != null) {
            dto.setDiscounts(invoice.getDiscounts().stream().map(discount -> {
                DiscountDto discountDto = new DiscountDto();
                discountDto.setId(discount.getId());
                discountDto.setServiceName(discount.getServiceName());
                discountDto.setPercentage(discount.getPercentage());
                discountDto.setDescription(discount.getDescription());
                return discountDto;
            }).collect(Collectors.toList()));
        }
        
        return dto;
    }
}