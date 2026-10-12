package com.xammer.billops.service;

import com.xammer.billops.dto.InvoiceDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers how an invoice's discounts are split between the consumption row and the
 * CloudFront row on the rendered invoice.
 *
 * The invariant under test is that the two rows together deduct exactly what
 * {@code recalculateTotals} deducted from the saved invoice. They used to disagree: the
 * CloudFront row applied no discount at all, and the consumption row stood in for it by
 * falling back to the invoice-wide discount total. A CloudFront discount therefore either
 * landed on the wrong row, or -- once a consumption-level discount also existed -- was
 * dropped from the PDF entirely and the customer was billed more than the invoice record
 * said.
 *
 * Figures mirror docs/samples/cloudfront-invoice-sample.md.
 */
class InvoiceDiscountAttributionTest {

    /** Discountable consumption: non-CloudFront line items less Marketplace. */
    private static final BigDecimal STD = new BigDecimal("8801.17");
    /** CloudFront at private rates. */
    private static final BigDecimal CF = new BigDecimal("3303.55");

    private static InvoiceDto.DiscountDto discount(String serviceName, String percentage) {
        InvoiceDto.DiscountDto d = new InvoiceDto.DiscountDto();
        d.setServiceName(serviceName);
        d.setPercentage(new BigDecimal(percentage));
        return d;
    }

    private static InvoiceDto.LineItemDto item(String serviceName, String cost) {
        InvoiceDto.LineItemDto i = new InvoiceDto.LineItemDto();
        i.setServiceName(serviceName);
        i.setCost(new BigDecimal(cost));
        return i;
    }

    private static final List<InvoiceDto.LineItemDto> STD_ITEMS = List.of(
            item("Amazon EC2", "4182.55"),
            item("Amazon RDS", "1864.20"),
            item("Amazon S3", "1109.87"),
            item("Amazon ElastiCache", "486.90"),
            item("Amazon CloudWatch", "168.72"),
            item("AWS Lambda", "312.44"),
            item("Data Transfer", "274.31"),
            item("Other services", "402.18"));

    /**
     * The case that was over-billing. Both discounts exist; both must be applied, each to
     * its own row.
     */
    @Test
    @DisplayName("consumption and CloudFront discounts are both applied, to separate rows")
    void consumptionAndCloudFrontDiscountsBothApply() {
        var result = InvoiceManagementService.attributeDiscounts(
                List.of(discount("AWS Consumption Charge", "8"),
                        discount("Amazon CloudFront", "12")),
                STD, CF, STD_ITEMS);

        assertThat(result.standard()).isEqualByComparingTo("704.09");
        assertThat(result.cloudFront()).isEqualByComparingTo("396.43");
        // Together they equal what recalculateTotals deducts from the saved invoice.
        assertThat(result.standard().add(result.cloudFront())).isEqualByComparingTo("1100.52");
        assertThat(result.showConsumptionPercent()).isTrue();
        assertThat(result.consumptionPercent()).isEqualByComparingTo("8");
    }

    /**
     * A CloudFront-only discount must not be deducted from the consumption row. The total
     * used to come out right while sitting on the wrong line, so the customer saw
     * CloudFront at full price and their other services discounted by CloudFront's
     * concession.
     */
    @Test
    @DisplayName("a CloudFront-only discount touches only the CloudFront row")
    void cloudFrontOnlyDiscountDoesNotReduceTheConsumptionRow() {
        var result = InvoiceManagementService.attributeDiscounts(
                List.of(discount("Amazon CloudFront", "12")), STD, CF, STD_ITEMS);

        assertThat(result.standard()).isEqualByComparingTo("0.00");
        assertThat(result.cloudFront()).isEqualByComparingTo("396.43");
        // Not a consumption-level scope, so no percentage is printed beside the row total.
        assertThat(result.showConsumptionPercent()).isFalse();
    }

    /**
     * An "ALL" discount is based on everything except Marketplace, CloudFront included, so
     * it has to reach both rows. Applying it only to the consumption row under-discounted
     * the invoice by CloudFront's share.
     */
    @Test
    @DisplayName("an ALL-scoped discount splits across both rows")
    void allScopedDiscountSplitsAcrossBothRows() {
        var result = InvoiceManagementService.attributeDiscounts(
                List.of(discount("ALL", "10")), STD, CF, STD_ITEMS);

        assertThat(result.standard()).isEqualByComparingTo("880.12");
        assertThat(result.cloudFront()).isEqualByComparingTo("330.36");

        // Same base as recalculateTotals -- 10% of (8801.17 + 3303.55) = 1210.4720 -- but
        // the two shares are rounded independently, because each invoice row converts to
        // INR on its own and has to be a settled 2dp figure. That leaves the sum up to one
        // paisa from the single-base result (1210.48 here against 1210.47). Unavoidable
        // once the base is split across rows, and bounded at 0.01 per row.
        assertThat(result.standard().add(result.cloudFront()))
                .isCloseTo(new BigDecimal("1210.47"), org.assertj.core.data.Offset.offset(new BigDecimal("0.01")));
        assertThat(result.showConsumptionPercent()).isTrue();
    }

    /** A named non-CloudFront service reduces the consumption row by that service alone. */
    @Test
    void perServiceDiscountAppliesToThatServiceOnly() {
        var result = InvoiceManagementService.attributeDiscounts(
                List.of(discount("Amazon EC2", "15")), STD, CF, STD_ITEMS);

        // 4182.55 * 0.15
        assertThat(result.standard()).isEqualByComparingTo("627.38");
        assertThat(result.cloudFront()).isEqualByComparingTo("0.00");
        assertThat(result.showConsumptionPercent()).isFalse();
    }

    /** Scope matching is case-insensitive, as elsewhere in the discount code. */
    @Test
    void scopeMatchingIsCaseInsensitive() {
        var result = InvoiceManagementService.attributeDiscounts(
                List.of(discount("amazon cloudfront", "12"),
                        discount("aws consumption charge", "8")),
                STD, CF, STD_ITEMS);

        assertThat(result.cloudFront()).isEqualByComparingTo("396.43");
        assertThat(result.standard()).isEqualByComparingTo("704.09");
    }

    /**
     * GCP and Azure consumption scopes were recognised only by the PDF, never by
     * {@code recalculateTotals}, where they fell through to the per-service branch and
     * matched nothing -- so the stored invoice carried a zero discount while the rendered
     * one showed a concession. Both paths now share {@code isConsumptionScope}.
     */
    @Test
    @DisplayName("Google Cloud Consumption is a consumption-level scope")
    void googleCloudConsumptionIsAConsumptionScope() {
        var result = InvoiceManagementService.attributeDiscounts(
                List.of(discount("Google Cloud Consumption", "8")), STD, BigDecimal.ZERO, STD_ITEMS);

        assertThat(result.standard()).isEqualByComparingTo("704.09");
        assertThat(result.showConsumptionPercent()).isTrue();
        assertThat(result.consumptionPercent()).isEqualByComparingTo("8");
    }

    @Test
    @DisplayName("Microsoft Azure Consumption is a consumption-level scope")
    void microsoftAzureConsumptionIsAConsumptionScope() {
        var result = InvoiceManagementService.attributeDiscounts(
                List.of(discount("Microsoft Azure Consumption", "5")), STD, BigDecimal.ZERO, STD_ITEMS);

        assertThat(result.standard()).isEqualByComparingTo("440.06");
        assertThat(result.showConsumptionPercent()).isTrue();
    }

    /** Several discounts on the same scope accumulate rather than the last one winning. */
    @Test
    void multipleDiscountsOnTheSameRowAccumulate() {
        var result = InvoiceManagementService.attributeDiscounts(
                List.of(discount("Amazon CloudFront", "10"),
                        discount("Amazon CloudFront", "2")),
                STD, CF, STD_ITEMS);

        // 330.355 + 66.071 = 396.426
        assertThat(result.cloudFront()).isEqualByComparingTo("396.43");
    }

    @Test
    void noDiscountsYieldsZeroForBothRows() {
        var result = InvoiceManagementService.attributeDiscounts(List.of(), STD, CF, STD_ITEMS);

        assertThat(result.standard()).isEqualByComparingTo("0");
        assertThat(result.cloudFront()).isEqualByComparingTo("0");
        assertThat(result.showConsumptionPercent()).isFalse();
    }

    @Test
    void nullDiscountListIsTreatedAsNone() {
        var result = InvoiceManagementService.attributeDiscounts(null, STD, CF, STD_ITEMS);

        assertThat(result.standard()).isEqualByComparingTo("0");
        assertThat(result.cloudFront()).isEqualByComparingTo("0");
    }

    // ── Charges shielded by manual private pricing ──────────────────────────
    //
    // The rendered invoice has to deduct exactly what recalculateTotals deducted, which
    // now means skipping charges billed at a rate an admin set. If the PDF kept
    // discounting them, the customer would be billed less than the invoice record says --
    // the same class of disagreement these tests were written for.

    /**
     * CloudFront at a private rate, shielded. An "ALL" discount reaches the consumption
     * row in full and the CloudFront row not at all.
     */
    @Test
    @DisplayName("an ALL discount skips the shielded CloudFront row")
    void allScopedDiscountSkipsShieldedCloudFront() {
        var result = InvoiceManagementService.attributeDiscounts(
                List.of(discount("ALL", "10")), STD, CF, STD_ITEMS,
                BigDecimal.ZERO, CF);

        assertThat(result.standard()).isEqualByComparingTo("880.12");
        assertThat(result.cloudFront()).isEqualByComparingTo("0.00");
    }

    /** Shielding inside the consumption row reduces that row's base, not CloudFront's. */
    @Test
    @DisplayName("an ALL discount skips shielded consumption charges")
    void allScopedDiscountSkipsShieldedConsumption() {
        BigDecimal shielded = new BigDecimal("4182.55"); // EC2 at a private rate

        var result = InvoiceManagementService.attributeDiscounts(
                List.of(discount("ALL", "10")), STD, CF, STD_ITEMS,
                shielded, BigDecimal.ZERO);

        // 10% of (8801.17 - 4182.55)
        assertThat(result.standard()).isEqualByComparingTo("461.86");
        assertThat(result.cloudFront()).isEqualByComparingTo("330.36");
    }

    @Test
    @DisplayName("a consumption-scoped discount skips shielded consumption charges")
    void consumptionScopedDiscountSkipsShieldedCharges() {
        var result = InvoiceManagementService.attributeDiscounts(
                List.of(discount("AWS Consumption Charge", "8")), STD, CF, STD_ITEMS,
                new BigDecimal("4182.55"), BigDecimal.ZERO);

        // 8% of (8801.17 - 4182.55)
        assertThat(result.standard()).isEqualByComparingTo("369.49");
        assertThat(result.showConsumptionPercent()).isTrue();
    }

    /**
     * The shield is against discounts that sweep the invoice without naming what is inside
     * it. A discount scoped to the service by name is the admin saying they mean it, so it
     * applies to the full priced amount.
     */
    @Test
    @DisplayName("a discount naming CloudFront still applies even when CloudFront is shielded")
    void aDiscountNamingTheServiceIgnoresTheShield() {
        var result = InvoiceManagementService.attributeDiscounts(
                List.of(discount("Amazon CloudFront", "12")), STD, CF, STD_ITEMS,
                BigDecimal.ZERO, CF);

        assertThat(result.cloudFront()).isEqualByComparingTo("396.43");
        assertThat(result.standard()).isEqualByComparingTo("0.00");
    }

    /** Everything shielded leaves an overall discount with nothing to reduce. */
    @Test
    void anEntirelyShieldedInvoiceDiscountsNothing() {
        var result = InvoiceManagementService.attributeDiscounts(
                List.of(discount("ALL", "10")), STD, CF, STD_ITEMS, STD, CF);

        assertThat(result.standard()).isEqualByComparingTo("0.00");
        assertThat(result.cloudFront()).isEqualByComparingTo("0.00");
    }

    /**
     * Shielding more than a row holds cannot invert into a discount. Not reachable through
     * the service -- the shielded figure is summed from the same lines as the row -- but
     * the clamp is what keeps a rounding disagreement from becoming a negative charge.
     */
    @Test
    void shieldingMoreThanTheRowHoldsClampsAtZero() {
        var result = InvoiceManagementService.attributeDiscounts(
                List.of(discount("ALL", "10")), STD, CF, STD_ITEMS,
                STD.add(new BigDecimal("1000")), CF.add(new BigDecimal("1000")));

        assertThat(result.standard()).isEqualByComparingTo("0.00");
        assertThat(result.cloudFront()).isEqualByComparingTo("0.00");
    }

    /** The four-argument form keeps meaning "nothing is shielded". */
    @Test
    void theShorterFormShieldsNothing() {
        var withoutShield = InvoiceManagementService.attributeDiscounts(
                List.of(discount("ALL", "10")), STD, CF, STD_ITEMS);
        var withZeroShield = InvoiceManagementService.attributeDiscounts(
                List.of(discount("ALL", "10")), STD, CF, STD_ITEMS,
                BigDecimal.ZERO, BigDecimal.ZERO);

        assertThat(withoutShield.standard()).isEqualByComparingTo(withZeroShield.standard());
        assertThat(withoutShield.cloudFront()).isEqualByComparingTo(withZeroShield.cloudFront());
    }

    /** A discount row with no percentage is skipped rather than throwing. */
    @Test
    void discountWithNullPercentageIsIgnored() {
        InvoiceDto.DiscountDto malformed = new InvoiceDto.DiscountDto();
        malformed.setServiceName("Amazon CloudFront");

        var result = InvoiceManagementService.attributeDiscounts(
                List.of(malformed, discount("Amazon CloudFront", "12")), STD, CF, STD_ITEMS);

        assertThat(result.cloudFront()).isEqualByComparingTo("396.43");
    }

    /**
     * With no CloudFront usage the CloudFront row is not rendered, and a CloudFront-scoped
     * discount must not leak onto the consumption row.
     */
    @Test
    void cloudFrontDiscountWithNoCloudFrontUsageDiscountsNothing() {
        var result = InvoiceManagementService.attributeDiscounts(
                List.of(discount("Amazon CloudFront", "12")), STD, BigDecimal.ZERO, STD_ITEMS);

        assertThat(result.cloudFront()).isEqualByComparingTo("0.00");
        assertThat(result.standard()).isEqualByComparingTo("0.00");
    }
}
