package com.xammer.billops.dto;

import java.math.BigDecimal;

/**
 * An admin's request to bill part of an invoice at a rate they set, rather than at
 * provider cost.
 *
 * <p>{@code serviceName} is required. {@code regionName} and {@code usageType} narrow the
 * scope and may be left blank for "all of them" -- blank and null are treated alike,
 * because an unselected dropdown posts an empty string.
 */
public class PrivatePricingRequestDto {

    private String serviceName;
    private String regionName;
    private String usageType;
    private BigDecimal unitRate;

    /** How the rate is quoted, e.g. "GB" or "Per 10000 Requests". Optional. */
    private String rateUnit;

    /**
     * Whether these charges are shielded from the invoice's overall discount. Defaults to
     * true: a negotiated rate carries its own margin, so discounting it again is nearly
     * always a mistake, and defaulting the other way would make that mistake silent.
     */
    private Boolean excludeFromOverallDiscount = Boolean.TRUE;

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
    public void setExcludeFromOverallDiscount(Boolean excludeFromOverallDiscount) {
        this.excludeFromOverallDiscount = excludeFromOverallDiscount;
    }
}
