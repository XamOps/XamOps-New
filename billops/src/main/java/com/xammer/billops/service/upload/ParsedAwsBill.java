package com.xammer.billops.service.upload;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * What {@link AwsBillPdfParser} read from an AWS console bill PDF ("AWS estimated bill
 * summary" / "Charges by service").
 *
 * <p>Every field is best-effort. The header values (account, period) are only ever
 * suggestions -- the admin confirms or overrides them before a draft is created -- and
 * the line items are what the draft starts from, editable like any other draft.
 */
public class ParsedAwsBill {

    /**
     * One rate line of "Charges by service".
     *
     * @param provider    the service-provider heading it sat under, if any
     * @param service     service name, normalised to the names CUR-built invoices use
     * @param region      region code ("ap-south-1") when the label was recognised, else the label
     * @param group       the usage group between region and rate line, if the bill had one
     * @param description the rate line's own text
     * @param quantity    usage quantity as printed, commas removed
     * @param unit        usage unit as printed
     * @param cost        amount in USD; negative for credits and Savings Plan negations
     * @param marketplace sat under an AWS Marketplace provider heading
     * @param synthetic   not a printed rate line: built from a section total because the
     *                    section printed no lines of its own (collapsed in the console)
     */
    public record Line(String provider, String service, String region, String group,
                       String description, String quantity, String unit, BigDecimal cost,
                       boolean marketplace, boolean synthetic, boolean rounding) {

        public Line(String provider, String service, String region, String group, String description,
                    String quantity, String unit, BigDecimal cost, boolean marketplace, boolean synthetic) {
            this(provider, service, region, group, description, quantity, unit, cost, marketplace, synthetic, false);
        }
    }

    /**
     * Description of a rounding line. AWS rounds each line and each subtotal to the cent on
     * its own, from unrounded amounts, so printed lines don't always add up to the printed
     * subtotal. A rounding line carries the difference so the draft matches both.
     */
    public static final String ROUNDING_DESCRIPTION = "Rounding difference to match the AWS bill's printed subtotal";

    /** Region label for rounding at the service and bill level, which belong to no region. */
    public static final String ROUNDING_REGION = "AWS bill rounding";

    /** Sum of the rounding lines. */
    public BigDecimal roundingTotal() {
        return lines.stream().filter(Line::rounding).map(Line::cost).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public long roundingLineCount() {
        return lines.stream().filter(Line::rounding).count();
    }

    private String accountId;
    private YearMonth billingPeriod;
    private LocalDate datePrinted;
    private boolean estimated;
    private boolean chargesSectionFound;
    private int pageCount;
    private BigDecimal grandTotal;
    private BigDecimal preTaxTotal;
    private BigDecimal taxTotal;
    private BigDecimal marketplaceTotal;
    private final List<Line> lines = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    /** Sum of every parsed line, credits included. */
    public BigDecimal lineTotal() {
        return lines.stream().map(Line::cost).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * The figure the line items should add up to: the pre-tax total when the bill prints
     * one, otherwise the grand total less any tax it prints. AWS taxes are left out of the
     * draft because BillOps adds its own GST on top.
     */
    public BigDecimal expectedLineTotal() {
        if (preTaxTotal != null) {
            return preTaxTotal;
        }
        if (grandTotal != null && taxTotal != null) {
            return grandTotal.subtract(taxTotal);
        }
        return grandTotal;
    }

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }
    public YearMonth getBillingPeriod() { return billingPeriod; }
    public void setBillingPeriod(YearMonth billingPeriod) { this.billingPeriod = billingPeriod; }
    public LocalDate getDatePrinted() { return datePrinted; }
    public void setDatePrinted(LocalDate datePrinted) { this.datePrinted = datePrinted; }
    public boolean isEstimated() { return estimated; }
    public void setEstimated(boolean estimated) { this.estimated = estimated; }
    public boolean isChargesSectionFound() { return chargesSectionFound; }
    public void setChargesSectionFound(boolean chargesSectionFound) { this.chargesSectionFound = chargesSectionFound; }
    public int getPageCount() { return pageCount; }
    public void setPageCount(int pageCount) { this.pageCount = pageCount; }
    public BigDecimal getGrandTotal() { return grandTotal; }
    public void setGrandTotal(BigDecimal grandTotal) { this.grandTotal = grandTotal; }
    public BigDecimal getPreTaxTotal() { return preTaxTotal; }
    public void setPreTaxTotal(BigDecimal preTaxTotal) { this.preTaxTotal = preTaxTotal; }
    public BigDecimal getTaxTotal() { return taxTotal; }
    public void setTaxTotal(BigDecimal taxTotal) { this.taxTotal = taxTotal; }
    public BigDecimal getMarketplaceTotal() { return marketplaceTotal; }
    public void setMarketplaceTotal(BigDecimal marketplaceTotal) { this.marketplaceTotal = marketplaceTotal; }
    public List<Line> getLines() { return lines; }
    public List<String> getWarnings() { return warnings; }
}
