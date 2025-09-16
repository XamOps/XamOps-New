package com.xammer.billops.dto;

import lombok.Data;

/**
 * This class represents the payload for applying a discount to an invoice preview.
 * It contains the invoice data and the discount to be applied.
 */
@Data
public class ApplyDiscountPreviewRequest {
    private InvoiceDto invoice;
    private DiscountRequestDto discount;
}