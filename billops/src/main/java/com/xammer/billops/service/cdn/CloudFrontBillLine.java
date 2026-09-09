package com.xammer.billops.service.cdn;

import java.math.BigDecimal;

/**
 * One priced CloudFront invoice line: a geography, a usage class, the quantity served,
 * and the rate it was billed at.
 *
 * Carries both {@code ratePerUnit} and {@code quantity} rather than only the total
 * because the invoice shows the working — "India, 1,240.50 GB @ $0.0170/GB = $21.09".
 * A CDN bill that shows only a total invites a support ticket asking how it was
 * derived, and the rate is the part a customer actually negotiated.
 *
 * {@code awsCost} is retained alongside the billed {@code amount}. It is not what the
 * customer pays, but it is the margin reference, and having it on the same line is what
 * makes a mispriced geography obvious.
 */
public class CloudFrontBillLine {

    private String geographyCode;
    private String geographyName;
    private String usageClass;

    private BigDecimal quantity = BigDecimal.ZERO;
    private String unit;

    /**
     * Quantity expressed in the units the rate is quoted in.
     *
     * Differs from {@link #quantity} only when the rate is quoted per a block — CloudFront
     * request pricing is conventionally "per 10,000 requests", so 200,000 requests is 20
     * billable units. Kept separate so the invoice can show the raw usage the customer
     * recognises while the arithmetic uses what was actually charged.
     */
    private BigDecimal billableUnits;

    /** Private rate applied. Null when no rate was configured and the line fell back to cost. */
    private BigDecimal ratePerUnit;
    private String rateUnit;
    private String rateDescription;

    /** What the customer is billed. */
    private BigDecimal amount = BigDecimal.ZERO;

    /** What AWS charged for the same usage — margin reference, not the billed figure. */
    private BigDecimal awsCost = BigDecimal.ZERO;

    /**
     * False when no private rate applied and the line was passed through at AWS cost.
     * Surfaced so an invoice with an unpriced geography can be caught before it is sent.
     */
    private boolean priced;

    /** Why a line is unpriced, shown to whoever reviews the invoice. */
    private String note;

    /** How many distinct CUR usage types folded into this line. Diagnostic. */
    private int usageTypeCount;

    public String getGeographyCode() { return geographyCode; }
    public void setGeographyCode(String geographyCode) { this.geographyCode = geographyCode; }

    public String getGeographyName() { return geographyName; }
    public void setGeographyName(String geographyName) { this.geographyName = geographyName; }

    public String getUsageClass() { return usageClass; }
    public void setUsageClass(String usageClass) { this.usageClass = usageClass; }

    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }

    public BigDecimal getBillableUnits() { return billableUnits; }
    public void setBillableUnits(BigDecimal billableUnits) { this.billableUnits = billableUnits; }

    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }

    public BigDecimal getRatePerUnit() { return ratePerUnit; }
    public void setRatePerUnit(BigDecimal ratePerUnit) { this.ratePerUnit = ratePerUnit; }

    public String getRateUnit() { return rateUnit; }
    public void setRateUnit(String rateUnit) { this.rateUnit = rateUnit; }

    public String getRateDescription() { return rateDescription; }
    public void setRateDescription(String rateDescription) { this.rateDescription = rateDescription; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public BigDecimal getAwsCost() { return awsCost; }
    public void setAwsCost(BigDecimal awsCost) { this.awsCost = awsCost; }

    public boolean isPriced() { return priced; }
    public void setPriced(boolean priced) { this.priced = priced; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

    public int getUsageTypeCount() { return usageTypeCount; }
    public void setUsageTypeCount(int usageTypeCount) { this.usageTypeCount = usageTypeCount; }

    /**
     * The line as it reads on the invoice, e.g.
     * "India — Data transfer out: 1,240.50 GB @ $0.017000/GB".
     * Falls back to a cost-passthrough wording when unpriced, so the invoice never
     * implies a rate that was not applied.
     */
    public String getDisplayDescription() {
        String geo = geographyName == null ? "" : geographyName;
        String what = describeClass();
        if (!priced || ratePerUnit == null) {
            return String.format("%s — %s: %s %s (at AWS cost)", geo, what, formatQuantity(), safeUnit());
        }
        // The rate's own unit is shown rather than the usage unit, so a rate quoted
        // "Per 10000 Requests" reads as such instead of implying a per-request price
        // ten thousand times too high.
        String rateUnitLabel = (rateUnit == null || rateUnit.isBlank()) ? safeUnit() : rateUnit;
        return String.format("%s — %s: %s %s @ $%s / %s",
                geo, what, formatQuantity(), safeUnit(),
                ratePerUnit.stripTrailingZeros().toPlainString(), rateUnitLabel);
    }

    private String describeClass() {
        if (usageClass == null) return "Usage";
        switch (usageClass) {
            case "DATA_TRANSFER_OUT": return "Data transfer out";
            case "REQUESTS":          return "Requests";
            case "INVALIDATION":      return "Invalidations";
            default:                   return "Other usage";
        }
    }

    private String formatQuantity() {
        return quantity == null ? "0" : String.format("%,.2f", quantity);
    }

    private String safeUnit() {
        return unit == null ? "" : unit;
    }
}
