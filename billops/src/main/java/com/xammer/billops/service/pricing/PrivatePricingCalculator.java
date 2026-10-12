package com.xammer.billops.service.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The arithmetic behind a manually applied private rate: {@code quantity x rate}, and the
 * decisions about when that multiplication must not be performed.
 *
 * <p>Pure and static so the money rules can be tested without a database, in the spirit of
 * {@code CloudFrontPricingService}, whose automatic CloudFront pricing this generalises.
 *
 * <h2>Why parsing is defensive</h2>
 * {@code InvoiceLineItem.usageQuantity} is a {@code String}, written by three different
 * paths: the report path emits a plain decimal from {@code stripTrailingZeros}
 * ({@code "0.000012"}, {@code "147128739"}), the Cost Explorer path emits
 * {@code String.format("%.3f", ...)}, and the region-only fallback writes the literal
 * {@code "1"} with unit {@code "N/A"} for a charge that has no measured quantity at all.
 * Treating an unreadable quantity as 1 -- which the older per-line rate editor does -- bills
 * a whole service at a single unit's price, so anything unreadable is refused here instead.
 */
public final class PrivatePricingCalculator {

    /** Matches a "per N units" rate quote, e.g. "Per 10000 Requests". */
    private static final Pattern PER_N = Pattern.compile("per\\s*([\\d,]+)", Pattern.CASE_INSENSITIVE);

    /** Scale of {@code InvoiceLineItem.cost}, so a priced amount round-trips exactly. */
    private static final int COST_SCALE = 4;

    private PrivatePricingCalculator() {
    }

    /** Why a line inside a rate's scope was left at provider cost. */
    public enum SkipReason {
        /** The quantity could not be read, so there is nothing trustworthy to multiply. */
        UNREADABLE_QUANTITY,
        /** No measured usage: a flat charge a per-unit rate cannot express. */
        NO_USAGE,
        /**
         * A credit or Savings Plan negation. Positive quantity, negative cost, so
         * repricing would turn money owed to the customer into money charged to them.
         */
        CREDIT
    }

    /**
     * The quantity to multiply, or null when the line carries none that can be trusted.
     *
     * <p>Takes the leading token so a quantity that was stored with its unit attached
     * ({@code "3009 hours"}) still reads, and strips grouping commas.
     */
    public static BigDecimal parseQuantity(String usageQuantity) {
        if (usageQuantity == null || usageQuantity.isBlank()) {
            return null;
        }
        String token = usageQuantity.trim().split("\\s+")[0].replace(",", "");
        if (token.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(token);
        } catch (NumberFormatException e) {
            // "N/A" and anything else non-numeric. Refused rather than defaulted.
            return null;
        }
    }

    /**
     * Whether this line must be left at provider cost, and why. Null means it can be priced.
     *
     * @param usageQuantity the line's stored quantity string
     * @param cost          the line's current amount, used only to recognise credits
     */
    public static SkipReason skipReason(String usageQuantity, BigDecimal cost) {
        if (cost != null && cost.signum() < 0) {
            // Checked before the quantity: a negation's quantity is perfectly readable,
            // which is exactly what makes it dangerous.
            return SkipReason.CREDIT;
        }
        BigDecimal quantity = parseQuantity(usageQuantity);
        if (quantity == null) {
            return SkipReason.UNREADABLE_QUANTITY;
        }
        if (quantity.signum() == 0) {
            return SkipReason.NO_USAGE;
        }
        return null;
    }

    /**
     * Divisor implied by a rate unit such as "Per 10000 Requests".
     *
     * <p>Request pricing is conventionally quoted per 10,000, so multiplying a raw request
     * count by that rate overcharges by four orders of magnitude. Returns 1 when the unit
     * names no quantity, including when it is malformed -- a bad unit must not silently
     * scale the bill.
     */
    public static BigDecimal unitDivisor(String rateUnit) {
        if (rateUnit == null || rateUnit.isBlank()) {
            return BigDecimal.ONE;
        }
        Matcher m = PER_N.matcher(rateUnit);
        if (m.find()) {
            try {
                BigDecimal n = new BigDecimal(m.group(1).replace(",", ""));
                if (n.signum() > 0) {
                    return n;
                }
            } catch (NumberFormatException ignored) {
                // Falls through to 1.
            }
        }
        return BigDecimal.ONE;
    }

    /**
     * The amount to bill for a quantity at a rate.
     *
     * <p>Division by the unit divisor keeps ten decimal places before the multiply, so a
     * per-10,000 rate on a small request count does not round to zero on the way through.
     */
    public static BigDecimal amountFor(BigDecimal quantity, BigDecimal unitRate, String rateUnit) {
        if (quantity == null || unitRate == null) {
            return null;
        }
        BigDecimal divisor = unitDivisor(rateUnit);
        BigDecimal billableUnits = divisor.compareTo(BigDecimal.ONE) == 0
                ? quantity
                : quantity.divide(divisor, 10, RoundingMode.HALF_UP);
        return billableUnits.multiply(unitRate).setScale(COST_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * A line's unit reduced to a comparable form, for checking that one rate can price a
     * whole scope.
     *
     * <p>Only case and surrounding whitespace are normalised. "GB" and "GB-Mo" stay
     * different because they are: one is a volume, the other a volume held for a month,
     * and one rate cannot price both.
     */
    public static String normaliseUnit(String unit) {
        if (unit == null) {
            return "";
        }
        return unit.trim().toLowerCase();
    }

    /** Whether a unit names no real measure, as the region-only fallback's "N/A" does. */
    public static boolean isPlaceholderUnit(String unit) {
        String u = normaliseUnit(unit);
        return u.isEmpty() || u.equals("n/a") || u.equals("na") || u.equals("none");
    }
}
