package com.xammer.cloud.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A rate the admin set by hand for part of one invoice, billed as
 * {@code quantity x unitRate} instead of the provider's cost.
 *
 * <p>This is the general form of what {@code CloudFrontPrivateRate} does for CDN traffic:
 * usage drives the charge and the provider's cost becomes a margin reference. CloudFront's
 * rates are per client and applied automatically while a draft is generated; these are per
 * invoice and applied deliberately by an admin afterwards, which is why they live beside
 * {@link Discount} rather than beside that table. Regenerating a draft starts over from
 * provider cost, exactly as it does for discounts.
 *
 * <h2>Scope</h2>
 * {@link #serviceName} is required; {@link #regionName} and {@link #usageType} narrow it,
 * and null in either means "every one of them". So the three levels of the bill's own
 * breakdown are all addressable: a whole service, a service in one region, or a single
 * usage type.
 *
 * <p>Scopes on an invoice are never allowed to overlap -- see {@link #overlaps} -- so each
 * charge is priced by at most one of these. That is what makes removal unambiguous: the
 * line's {@code originalCost} can be restored without asking which other rate might also
 * have a claim on it.
 *
 * <h2>Relationship to discounts</h2>
 * A negotiated rate already carries its own margin, so stacking the invoice's overall
 * discount on top of it would concede that margin twice. {@link #excludeFromOverallDiscount}
 * is the admin's switch for that, and it shields these charges from invoice-wide and
 * consumption-wide discounts only. A discount aimed at this service by name still applies:
 * the admin named it, so they meant it.
 */
@Entity
@Table(name = "invoice_private_pricing", uniqueConstraints = {
        @UniqueConstraint(name = "uk_invoice_private_pricing_scope",
                columnNames = {"invoice_id", "service_name", "region_name", "usage_type"})
})
public class InvoicePrivatePricing {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invoice_id", nullable = false)
    private Invoice invoice;

    /** The service whose charges this rate prices, exactly as the line items name it. */
    @Column(name = "service_name", nullable = false)
    private String serviceName;

    /** One region, or null for every region the service is billed in. */
    @Column(name = "region_name")
    private String regionName;

    /** One usage type, or null for every usage type of the service. */
    @Column(name = "usage_type")
    private String usageType;

    /**
     * Price per unit of usage.
     *
     * <p>Same precision as {@code CloudFrontPrivateRate.privateRate}: real CDN and request
     * rates run to several significant figures below a cent, and rounding the rate before
     * multiplying it by a quantity in the millions moves the charge by real money.
     */
    @Column(name = "unit_rate", nullable = false, precision = 19, scale = 10)
    private BigDecimal unitRate;

    /**
     * The unit the rate is quoted in, for display and for the "per N" divisor.
     *
     * <p>"Per 10000 Requests" is honoured as a divisor, because request pricing is
     * conventionally quoted that way and multiplying a raw request count by such a rate
     * overcharges by four orders of magnitude.
     */
    @Column(name = "rate_unit")
    private String rateUnit;

    /**
     * Whether these charges are shielded from invoice-wide and consumption-wide discounts.
     *
     * <p>A wrapper with a column default rather than a primitive: billops runs
     * {@code ddl-auto=update}, and the pattern is already established by
     * {@code Invoice.provisional} for exactly this reason.
     */
    @Column(name = "exclude_from_overall_discount", columnDefinition = "boolean default true")
    private Boolean excludeFromOverallDiscount = Boolean.TRUE;

    /** How many charge lines this rate repriced. */
    @Column(name = "line_count")
    private Integer lineCount;

    /**
     * Lines inside the scope that were left at provider cost, and why.
     *
     * <p>Kept because the alternative is an admin assuming the whole scope was repriced.
     * Credits and Savings Plan negations are the usual cause: they carry a positive
     * quantity and a negative cost, so repricing them would turn a credit into a charge.
     */
    @Column(name = "skipped_line_count")
    private Integer skippedLineCount;

    /** What the repriced lines cost at provider rates, for the margin comparison. */
    @Column(name = "original_amount", precision = 19, scale = 4)
    private BigDecimal originalAmount;

    /** What those same lines bill at this rate. */
    @Column(name = "priced_amount", precision = 19, scale = 4)
    private BigDecimal pricedAmount;

    @Column(name = "description")
    private String description;

    @Column(name = "applied_at")
    private Instant appliedAt;

    /**
     * Whether a charge line falls inside this rate's scope.
     *
     * <p>Case-insensitive on every level, like the discount scope rules, because service
     * names reach line items from several ingestion paths and have differed in case.
     */
    public boolean matches(String lineService, String lineRegion, String lineUsageType) {
        if (!equalsIgnoreCaseNullSafe(serviceName, lineService)) {
            return false;
        }
        if (regionName != null && !equalsIgnoreCaseNullSafe(regionName, lineRegion)) {
            return false;
        }
        return usageType == null || equalsIgnoreCaseNullSafe(usageType, lineUsageType);
    }

    public boolean matches(InvoiceLineItem item) {
        return item != null && matches(item.getServiceName(), item.getRegionName(), item.getUsageType());
    }

    /**
     * Whether two scopes could claim the same charge line.
     *
     * <p>True when they name the same service and neither narrows the other out: a
     * service-wide rate overlaps every narrower rate on that service, and an unset level
     * overlaps any value of it. Applying an overlapping scope is refused rather than
     * resolved by specificity, so no line is ever priced twice and removing one rate
     * cannot strand a line at another rate's price.
     */
    public boolean overlaps(InvoicePrivatePricing other) {
        if (other == null || !equalsIgnoreCaseNullSafe(serviceName, other.serviceName)) {
            return false;
        }
        boolean regionOverlaps = regionName == null || other.regionName == null
                || equalsIgnoreCaseNullSafe(regionName, other.regionName);
        boolean usageOverlaps = usageType == null || other.usageType == null
                || equalsIgnoreCaseNullSafe(usageType, other.usageType);
        return regionOverlaps && usageOverlaps;
    }

    /** "Amazon CloudFront · US East (N. Virginia) · DataTransfer-Out-Bytes" */
    public String scopeLabel() {
        StringBuilder sb = new StringBuilder(serviceName == null ? "" : serviceName);
        if (regionName != null && !regionName.isBlank()) {
            sb.append(" · ").append(regionName);
        }
        if (usageType != null && !usageType.isBlank()) {
            sb.append(" · ").append(usageType);
        }
        return sb.toString();
    }

    private static boolean equalsIgnoreCaseNullSafe(String a, String b) {
        return a == null ? b == null : a.equalsIgnoreCase(b);
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Invoice getInvoice() { return invoice; }
    public void setInvoice(Invoice invoice) { this.invoice = invoice; }
    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }
    public String getRegionName() { return regionName; }
    public void setRegionName(String regionName) { this.regionName = regionName; }
    public String getUsageType() { return usageType; }
    public void setUsageType(String usageType) { this.usageType = usageType; }
    public BigDecimal getUnitRate() { return unitRate; }
    public void setUnitRate(BigDecimal unitRate) { this.unitRate = unitRate; }
    public String getRateUnit() { return rateUnit; }
    public void setRateUnit(String rateUnit) { this.rateUnit = rateUnit; }
    public boolean isExcludeFromOverallDiscount() { return !Boolean.FALSE.equals(excludeFromOverallDiscount); }
    public void setExcludeFromOverallDiscount(boolean excludeFromOverallDiscount) {
        this.excludeFromOverallDiscount = excludeFromOverallDiscount;
    }
    public Integer getLineCount() { return lineCount; }
    public void setLineCount(Integer lineCount) { this.lineCount = lineCount; }
    public Integer getSkippedLineCount() { return skippedLineCount; }
    public void setSkippedLineCount(Integer skippedLineCount) { this.skippedLineCount = skippedLineCount; }
    public BigDecimal getOriginalAmount() { return originalAmount; }
    public void setOriginalAmount(BigDecimal originalAmount) { this.originalAmount = originalAmount; }
    public BigDecimal getPricedAmount() { return pricedAmount; }
    public void setPricedAmount(BigDecimal pricedAmount) { this.pricedAmount = pricedAmount; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Instant getAppliedAt() { return appliedAt; }
    public void setAppliedAt(Instant appliedAt) { this.appliedAt = appliedAt; }
}
