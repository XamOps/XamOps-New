package com.xammer.billops.dto;

import java.math.BigDecimal;

/**
 * Body for {@code PUT /api/admin/invoices/{id}/holding-cost}: the editable, optional
 * percentage markup the admin applies to the invoice's AWS Marketplace charge.
 *
 * <p>Its own request type rather than an overload of {@code InvoiceUpdateDto} (which is
 * strictly line-item edits) or {@code DiscountRequestDto} (a holding cost is a markup, not
 * a negative discount).
 */
public class HoldingCostRequestDto {
    /** Percent units (15.00 = 15%). Null or absent clears the holding cost (treated as 0). */
    private BigDecimal percent;

    public BigDecimal getPercent() {
        return percent;
    }

    public void setPercent(BigDecimal percent) {
        this.percent = percent;
    }
}
