package com.xammer.cloud.dto.azure;

/**
 * A single wasteful or idle Azure resource detected by {@code AzureWasteService}.
 *
 * <p>{@link #monthlySavings} is denominated in {@link #currency}, which is always USD as
 * produced by the detectors. It previously carried INR values from a hardcoded table while
 * the dashboard mapped some of the same items through a USD conversion, so adjacent fields
 * in one response differed by roughly 83x.
 *
 * <p>Savings are derived from Azure <em>list</em> prices and exclude any Reserved Instance,
 * Savings Plan or Azure Hybrid Benefit discount already applied to the subscription, so
 * they represent an upper bound.
 */
public class AzureWasteItem {

    /** Currency code for amounts produced by the waste detectors. */
    public static final String BASE_CURRENCY = "USD";

    private String resourceId;
    private String resourceName;
    /** e.g. "Unattached Disk", "Stopped VM", "Deallocated VM", "Orphaned Public IP". */
    private String resourceType;
    private String region;
    private String reason;
    /** Estimated monthly savings in {@link #currency} if the resource is removed. */
    private double monthlySavings;
    /** ISO-4217 code for {@link #monthlySavings}. */
    private String currency = BASE_CURRENCY;
    /**
     * True when the cost impact is real but could not be quantified, so
     * {@link #monthlySavings} is a placeholder zero. Consumers must exclude these from
     * savings totals rather than reporting the resource as free.
     */
    private boolean savingsUnknown;

    public AzureWasteItem() {}

    public AzureWasteItem(String resourceId, String resourceName, String resourceType,
                          String region, String reason, double monthlySavings) {
        this.resourceId = resourceId;
        this.resourceName = resourceName;
        this.resourceType = resourceType;
        this.region = region;
        this.reason = reason;
        this.monthlySavings = monthlySavings;
        this.currency = BASE_CURRENCY;
        this.savingsUnknown = false;
    }

    /**
     * Creates an item whose cost impact is real but not quantified, so it is reported as a
     * finding without contributing a misleading zero to savings totals.
     */
    public static AzureWasteItem withUnknownSavings(String resourceId, String resourceName,
                                                    String resourceType, String region, String reason) {
        AzureWasteItem item = new AzureWasteItem(resourceId, resourceName, resourceType, region, reason, 0.0);
        item.setSavingsUnknown(true);
        return item;
    }

    /**
     * True when Azure does not bill for the resource, so cleaning it up frees no spend.
     *
     * <p>NICs, network security groups, availability sets, route tables and empty resource
     * groups are all free. These were previously emitted via {@link #withUnknownSavings},
     * which claims the cost is real but unmeasured — overstating the value of acting on them.
     *
     * <p>Distinct from {@link #savingsUnknown}: that means real cost, unpriced; this means
     * the cost is known to be zero.
     */
    private boolean noCostImpact;

    /**
     * Creates a hygiene finding for a resource that Azure does not bill for.
     *
     * @see #noCostImpact
     */
    public static AzureWasteItem withNoCostImpact(String resourceId, String resourceName,
                                                  String resourceType, String region, String reason) {
        AzureWasteItem item = new AzureWasteItem(resourceId, resourceName, resourceType, region, reason, 0.0);
        item.setNoCostImpact(true);
        return item;
    }

    public boolean isNoCostImpact() { return noCostImpact; }
    public void setNoCostImpact(boolean noCostImpact) { this.noCostImpact = noCostImpact; }

    /** True when this finding should contribute to a reclaimable-spend total. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isBillableWaste() {
        return !savingsUnknown && !noCostImpact;
    }

    public String getResourceId() { return resourceId; }
    public void setResourceId(String resourceId) { this.resourceId = resourceId; }

    public String getResourceName() { return resourceName; }
    public void setResourceName(String resourceName) { this.resourceName = resourceName; }

    public String getResourceType() { return resourceType; }
    public void setResourceType(String resourceType) { this.resourceType = resourceType; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public double getMonthlySavings() { return monthlySavings; }
    public void setMonthlySavings(double monthlySavings) { this.monthlySavings = monthlySavings; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public boolean isSavingsUnknown() { return savingsUnknown; }
    public void setSavingsUnknown(boolean savingsUnknown) { this.savingsUnknown = savingsUnknown; }
}
