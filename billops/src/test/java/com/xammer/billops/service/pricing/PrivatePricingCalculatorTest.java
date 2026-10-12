package com.xammer.billops.service.pricing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The arithmetic of a manually applied private rate, and the cases where multiplying must
 * not happen.
 *
 * <p>Every refusal here exists because the alternative silently bills the wrong amount.
 * {@code InvoiceLineItem.usageQuantity} is a String written by three different generation
 * paths, and the older per-line rate editor treats anything it cannot parse as a quantity
 * of 1 -- which prices a whole service at one unit's rate.
 */
class PrivatePricingCalculatorTest {

    // ── Quantity parsing ────────────────────────────────────────────────────

    @Test
    @DisplayName("reads the quantity formats the three generation paths actually write")
    void readsRealQuantityFormats() {
        // Report path: stripTrailingZeros, so anything from millions to millionths.
        assertThat(PrivatePricingCalculator.parseQuantity("147128739")).isEqualByComparingTo("147128739");
        assertThat(PrivatePricingCalculator.parseQuantity("0.000012")).isEqualByComparingTo("0.000012");
        // Cost Explorer path: String.format("%.3f", ...).
        assertThat(PrivatePricingCalculator.parseQuantity("9780.000")).isEqualByComparingTo("9780");
        // Region-only fallback.
        assertThat(PrivatePricingCalculator.parseQuantity("1")).isEqualByComparingTo("1");
        // Quantity stored with its unit attached, and with grouping separators.
        assertThat(PrivatePricingCalculator.parseQuantity("3009 hours")).isEqualByComparingTo("3009");
        assertThat(PrivatePricingCalculator.parseQuantity("1,323")).isEqualByComparingTo("1323");
        // Savings Plan negations are negative amounts, not negative quantities, but a
        // negative quantity still reads rather than throwing.
        assertThat(PrivatePricingCalculator.parseQuantity("-5")).isEqualByComparingTo("-5");
    }

    @Test
    @DisplayName("an unreadable quantity is null, never 1")
    void unreadableQuantityIsNull() {
        assertThat(PrivatePricingCalculator.parseQuantity(null)).isNull();
        assertThat(PrivatePricingCalculator.parseQuantity("")).isNull();
        assertThat(PrivatePricingCalculator.parseQuantity("   ")).isNull();
        assertThat(PrivatePricingCalculator.parseQuantity("N/A")).isNull();
        assertThat(PrivatePricingCalculator.parseQuantity("unknown")).isNull();
    }

    // ── What must not be priced ─────────────────────────────────────────────

    @Test
    @DisplayName("a credit is refused before its quantity is even considered")
    void creditsAreRefused() {
        // A SavingsPlanNegation line: 6.793 hours at -$0.28. The quantity is perfectly
        // readable, which is exactly what makes multiplying it dangerous -- it would turn
        // money owed to the customer into money charged to them.
        assertThat(PrivatePricingCalculator.skipReason("6.793", new BigDecimal("-0.28")))
                .isEqualTo(PrivatePricingCalculator.SkipReason.CREDIT);
    }

    @Test
    void zeroUsageAndUnreadableQuantitiesAreRefused() {
        assertThat(PrivatePricingCalculator.skipReason("0", new BigDecimal("12.00")))
                .isEqualTo(PrivatePricingCalculator.SkipReason.NO_USAGE);
        assertThat(PrivatePricingCalculator.skipReason("N/A", new BigDecimal("12.00")))
                .isEqualTo(PrivatePricingCalculator.SkipReason.UNREADABLE_QUANTITY);
        assertThat(PrivatePricingCalculator.skipReason(null, new BigDecimal("12.00")))
                .isEqualTo(PrivatePricingCalculator.SkipReason.UNREADABLE_QUANTITY);
    }

    @Test
    void anOrdinaryChargeIsPriceable() {
        assertThat(PrivatePricingCalculator.skipReason("9780", new BigDecimal("43.47"))).isNull();
        // Zero cost is fine: usage that happened to be free at provider rates is still
        // usage, and a private rate may well charge for it.
        assertThat(PrivatePricingCalculator.skipReason("100", BigDecimal.ZERO)).isNull();
    }

    // ── The multiply ────────────────────────────────────────────────────────

    @Test
    @DisplayName("amount is quantity x rate, at the line item's own scale")
    void amountIsQuantityTimesRate() {
        // 9,780 GB at $0.085
        assertThat(PrivatePricingCalculator.amountFor(
                new BigDecimal("9780"), new BigDecimal("0.085"), "GB"))
                .isEqualByComparingTo("831.3000");

        // Scale 4, matching InvoiceLineItem.cost, so the figure round-trips unchanged.
        assertThat(PrivatePricingCalculator.amountFor(
                new BigDecimal("3"), new BigDecimal("0.00001"), "GB").scale()).isEqualTo(4);
    }

    @Test
    @DisplayName("a rate quoted per 10,000 requests is divided, not multiplied raw")
    void perTenThousandRequestsIsDivided() {
        // 147,128,739 requests at $0.01 per 10,000 is $147.13 -- not $1,471,287.39.
        BigDecimal amount = PrivatePricingCalculator.amountFor(
                new BigDecimal("147128739"), new BigDecimal("0.01"), "Per 10000 Requests");
        assertThat(amount).isEqualByComparingTo("147.1287");

        // The same rate applied without the divisor would be four orders of magnitude out.
        assertThat(PrivatePricingCalculator.amountFor(
                new BigDecimal("147128739"), new BigDecimal("0.01"), "Requests"))
                .isEqualByComparingTo("1471287.39");
    }

    @Test
    void unitDivisorReadsOnlyAQuantityItCanTrust() {
        assertThat(PrivatePricingCalculator.unitDivisor("Per 10000 Requests")).isEqualByComparingTo("10000");
        assertThat(PrivatePricingCalculator.unitDivisor("per 1,000 requests")).isEqualByComparingTo("1000");
        assertThat(PrivatePricingCalculator.unitDivisor("GB")).isEqualByComparingTo("1");
        assertThat(PrivatePricingCalculator.unitDivisor(null)).isEqualByComparingTo("1");
        assertThat(PrivatePricingCalculator.unitDivisor("")).isEqualByComparingTo("1");
        // A malformed or zero quantity falls back to 1 rather than scaling the bill by it.
        assertThat(PrivatePricingCalculator.unitDivisor("per 0 requests")).isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("a small count against a per-10,000 rate does not round to zero on the way through")
    void smallCountsSurviveTheDivision() {
        // 3 requests at $0.01 per 10,000 = $0.000003, which must not become $0.0000.
        // It rounds to the line item's 4 decimal places, but through a 10-place division
        // rather than being lost before the multiply.
        assertThat(PrivatePricingCalculator.amountFor(
                new BigDecimal("3000"), new BigDecimal("0.01"), "Per 10000 Requests"))
                .isEqualByComparingTo("0.0030");
    }

    @Test
    void amountIsNullWhenThereIsNothingToMultiply() {
        assertThat(PrivatePricingCalculator.amountFor(null, BigDecimal.ONE, "GB")).isNull();
        assertThat(PrivatePricingCalculator.amountFor(BigDecimal.ONE, null, "GB")).isNull();
    }

    // ── Unit comparison ─────────────────────────────────────────────────────

    @Test
    @DisplayName("units differing only in case are the same; GB and GB-Mo are not")
    void unitNormalisationIsOnlyAboutCase() {
        assertThat(PrivatePricingCalculator.normaliseUnit(" GB "))
                .isEqualTo(PrivatePricingCalculator.normaliseUnit("gb"));
        // One is a volume, the other a volume held for a month. One rate cannot price both.
        assertThat(PrivatePricingCalculator.normaliseUnit("GB"))
                .isNotEqualTo(PrivatePricingCalculator.normaliseUnit("GB-Mo"));
    }

    @Test
    void placeholderUnitsNameNoMeasure() {
        assertThat(PrivatePricingCalculator.isPlaceholderUnit("N/A")).isTrue();
        assertThat(PrivatePricingCalculator.isPlaceholderUnit("")).isTrue();
        assertThat(PrivatePricingCalculator.isPlaceholderUnit(null)).isTrue();
        assertThat(PrivatePricingCalculator.isPlaceholderUnit("GB")).isFalse();
        assertThat(PrivatePricingCalculator.isPlaceholderUnit("hours")).isFalse();
    }
}
