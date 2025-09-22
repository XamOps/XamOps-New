package com.xammer.billops.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

/**
 * This DTO is used as the request body when an administrator
 * updates an existing draft invoice. It contains only the fields
 * that are editable on the frontend.
 */
@Data
public class InvoiceUpdateDto {

    private List<LineItemUpdateDto> lineItems;

    /**
     * Represents a single line item within the update payload.
     * Contains all the editable fields for an invoice line item.
     */
    @Data
    public static class LineItemUpdateDto {
        private String serviceName;
        private String regionName;
        private String resourceName;
        private String usageQuantity;
        private String unit;
        private BigDecimal cost;
                private boolean hidden;

    }
}