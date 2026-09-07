package com.xammer.billops.service.cdn;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * The priced CloudFront portion of an invoice.
 *
 * Exposes the billed total, AWS's own cost for the same usage, and whether every line
 * was actually priced. That last flag is the point: an invoice containing traffic from
 * a geography with no configured rate is not wrong so much as incomplete, and it should
 * be caught by whoever reviews it rather than discovered by the customer.
 */
public class CloudFrontBill {

    private final List<CloudFrontBillLine> lines = new ArrayList<>();

    public List<CloudFrontBillLine> getLines() {
        return lines;
    }

    public boolean isEmpty() {
        return lines.isEmpty();
    }

    /** What the customer is billed for CDN, at private rates. */
    public BigDecimal getTotalAmount() {
        return lines.stream()
                .map(l -> l.getAmount() == null ? BigDecimal.ZERO : l.getAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    /** What AWS charged for the same usage. Margin reference, never billed directly. */
    public BigDecimal getTotalAwsCost() {
        return lines.stream()
                .map(l -> l.getAwsCost() == null ? BigDecimal.ZERO : l.getAwsCost())
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Billed total minus AWS cost.
     *
     * Can legitimately be negative — a private rate below AWS list is the whole point
     * of the agreement, and a geography priced under cost is a commercial decision
     * rather than a bug. Surfaced so it is visible either way.
     */
    public BigDecimal getMargin() {
        return getTotalAmount().subtract(getTotalAwsCost());
    }

    /** True when at least one line had no configured rate and fell back to AWS cost. */
    public boolean hasUnpricedLines() {
        return lines.stream().anyMatch(l -> !l.isPriced());
    }

    public List<CloudFrontBillLine> getUnpricedLines() {
        return lines.stream().filter(l -> !l.isPriced()).collect(java.util.stream.Collectors.toList());
    }
}
