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
    private BigDecimal finalTotal;
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
    }

    @Data
    public static class DiscountDto {
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
        dto.setFinalTotal(invoice.getFinalTotal());
        dto.setAccountName(invoice.getCloudAccount().getAccountName());
        dto.setAwsAccountId(invoice.getCloudAccount().getAwsAccountId());

        dto.setLineItems(invoice.getLineItems().stream().map(item -> {
            LineItemDto itemDto = new LineItemDto();
            itemDto.setServiceName(item.getServiceName());
            itemDto.setRegionName(item.getRegionName());
            itemDto.setResourceName(item.getResourceName());
            itemDto.setUsageQuantity(item.getUsageQuantity());
            itemDto.setUnit(item.getUnit());
            itemDto.setCost(item.getCost());
            return itemDto;
        }).collect(Collectors.toList()));

        dto.setDiscounts(invoice.getDiscounts().stream().map(discount -> {
            DiscountDto discountDto = new DiscountDto();
            discountDto.setServiceName(discount.getServiceName());
            discountDto.setPercentage(discount.getPercentage());
            discountDto.setDescription(discount.getDescription());
            return discountDto;
        }).collect(Collectors.toList()));

        return dto;
    }
}