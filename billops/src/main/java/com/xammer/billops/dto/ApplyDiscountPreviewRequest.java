package com.xammer.billops.dto;

import lombok.Data;

@Data
public class ApplyDiscountPreviewRequest {
    private InvoiceDto invoice;
    private DiscountRequestDto discount;

    public InvoiceDto getInvoice() { return invoice; }
    public void setInvoice(InvoiceDto invoice) { this.invoice = invoice; }
    public DiscountRequestDto getDiscount() { return discount; }
    public void setDiscount(DiscountRequestDto discount) { this.discount = discount; }
}