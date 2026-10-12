package com.xammer.cloud.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;import java.math.BigDecimal;

@Entity
public class InvoiceLineItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String serviceName;
    private String regionName;
    private String resourceName;
    private String usageQuantity;
    private String unit;

    /**
     * The usage type a rate line belongs to — "APS3-BoxUsage:m7i.xlarge" — on invoices
     * built from the Cost and Usage Report, where {@code resourceName} carries the rate
     * line's own description. It is the third level of the bill's breakdown (service,
     * region, usage type, rate line), and without it the detail page could only list
     * descriptions straight under a region.
     *
     * Null on invoices built from Cost Explorer and on everything generated before this
     * existed; those keep rendering as service, region, line.
     */
    @Column(name = "usage_type")
    private String usageType;

    @Column(precision = 19, scale = 4)
    private BigDecimal cost;

    /**
     * What this line cost at provider rates, kept only once a private rate has replaced
     * {@link #cost}. Null on every line billed at provider cost, which is almost all of
     * them.
     *
     * <p>Private pricing overwrites {@code cost} in place so that totals, the PDF and the
     * detail bill all follow without knowing anything about it. Without the original here
     * that overwrite would be one-way: removing the rate could not put the invoice back,
     * and there would be nothing to show the margin against.
     *
     * <p>Nullable on purpose. billops runs {@code ddl-auto=update}, which can add a
     * nullable column to the populated {@code invoice_line_item} table but would fail on a
     * NOT NULL one.
     */
    @Column(name = "original_cost", precision = 19, scale = 4)
    private BigDecimal originalCost;

    /**
     * The per-unit rate {@link #cost} was computed from, when an admin set one.
     *
     * <p>Doubles as the marker for "this line is privately priced", which is what shields
     * it from invoice-wide discounts and badges it on the detail page. Distinct from
     * {@code InvoiceDto.LineItemDto.unitRate}, which is derived the other way round --
     * cost divided by quantity -- for display.
     */
    @Column(name = "private_unit_rate", precision = 19, scale = 10)
    private BigDecimal privateUnitRate;

    /**
     * The unit {@link #privateUnitRate} was quoted in — "GB", "Hrs", "Per 10000 Requests".
     *
     * <p>Held on the line, not only on the rate record, because the detail bill prints one
     * row per line and has nothing but the line in hand at that point. Without it the bill
     * would have to print the rate against {@link #unit}, which is wrong by the quoted
     * divisor: $0.01 per 10,000 requests shown as "$0.01 per Requests" overstates the rate
     * by four orders of magnitude next to a raw request count.
     */
    @Column(name = "private_rate_unit")
    private String privateRateUnit;

    // --- START: NEW FIELD ---
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean hidden = false;
    // --- END: NEW FIELD ---

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invoice_id", nullable = false)
    private Invoice invoice;

    // Getters and Setters
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
    public String getUsageType() { return usageType; }
    public void setUsageType(String usageType) { this.usageType = usageType; }
    public BigDecimal getCost() { return cost; }
    public void setCost(BigDecimal cost) { this.cost = cost; }
    public BigDecimal getOriginalCost() { return originalCost; }
    public void setOriginalCost(BigDecimal originalCost) { this.originalCost = originalCost; }
    public BigDecimal getPrivateUnitRate() { return privateUnitRate; }
    public void setPrivateUnitRate(BigDecimal privateUnitRate) { this.privateUnitRate = privateUnitRate; }
    public String getPrivateRateUnit() { return privateRateUnit; }
    public void setPrivateRateUnit(String privateRateUnit) { this.privateRateUnit = privateRateUnit; }
    /** Whether an admin's private rate produced this line's amount. */
    public boolean isPrivatelyPriced() { return privateUnitRate != null; }
    public Invoice getInvoice() { return invoice; }
    public void setInvoice(Invoice invoice) { this.invoice = invoice; }

    // --- START: GETTER/SETTER FOR NEW FIELD ---
    public boolean isHidden() { return hidden; }
    public void setHidden(boolean hidden) { this.hidden = hidden; }
    // --- END: GETTER/SETTER FOR NEW FIELD ---
}