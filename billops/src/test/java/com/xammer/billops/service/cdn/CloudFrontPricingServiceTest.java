package com.xammer.billops.service.cdn;

import com.xammer.billops.domain.CloudFrontPrivateRate;
import com.xammer.billops.dto.cur.CurAggregates;
import com.xammer.billops.repository.CloudFrontPrivateRateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers CloudFront private-rate pricing against the existing
 * {@link CloudFrontPrivateRate} table.
 *
 * These figures go on customer invoices, computed from usage quantity rather than AWS
 * cost, so the cases that matter are the ones that would produce a plausible-looking
 * wrong number: a rate borrowed from the wrong geography, a per-GB rate applied to
 * requests, a per-10,000 rate multiplied by a raw request count, or usage quietly
 * billed at zero because no rate matched.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CloudFrontPricingServiceTest {

    private static final Long CLIENT = 42L;

    private CloudFrontPrivateRateRepository rateRepository;
    private CloudFrontPricingService service;

    @BeforeEach
    void setUp() {
        rateRepository = mock(CloudFrontPrivateRateRepository.class);
        service = new CloudFrontPricingService(rateRepository);
        when(rateRepository.findByClientId(anyLong())).thenReturn(List.of());
    }

    private void withRates(CloudFrontPrivateRate... rates) {
        when(rateRepository.findByClientId(CLIENT)).thenReturn(List.of(rates));
    }

    private static CloudFrontPrivateRate rate(String region, String usageType, String unit, String perUnit) {
        CloudFrontPrivateRate r = new CloudFrontPrivateRate();
        r.setRegionMatcher(region);
        r.setUsageTypeMatcher(usageType);
        r.setUnit(unit);
        r.setPrivateRate(new BigDecimal(perUnit));
        return r;
    }

    private static CurAggregates.SvcUsageEntry usage(String usageType, double cost, double qty, String unit) {
        return new CurAggregates.SvcUsageEntry("Amazon CloudFront", "global", usageType, cost, qty, unit);
    }

    // ── Core pricing ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("bills quantity at the private rate, not AWS cost")
    void pricesFromQuantityNotCost() {
        withRates(rate("US", "DataTransfer-Out", "GB", "0.045"));

        CloudFrontBill bill = service.price(
                List.of(usage("US-DataTransfer-Out-Bytes", 8.50, 1000.0, "GB")), CLIENT);

        CloudFrontBillLine line = bill.getLines().get(0);
        // 1000 GB x $0.045 = $45.00 — AWS's $8.50 is not the base.
        assertThat(line.getAmount()).isEqualByComparingTo("45.00");
        assertThat(line.getAwsCost()).isEqualByComparingTo("8.50");
        assertThat(line.isPriced()).isTrue();
        assertThat(bill.getMargin()).isEqualByComparingTo("36.50");
    }

    @Test
    @DisplayName("each geography bills at its own rate")
    void pricesEachGeographySeparately() {
        withRates(
                rate("US", "Bandwidth", "GB", "0.045"),
                rate("IN", "Bandwidth", "GB", "0.109"));

        CloudFrontBill bill = service.price(List.of(
                usage("US-DataTransfer-Out-Bytes", 8.50, 1000.0, "GB"),
                usage("IN-DataTransfer-Out-Bytes", 17.00, 1000.0, "GB")), CLIENT);

        assertThat(bill.getLines()).hasSize(2);
        assertThat(lineFor(bill, "IN").getAmount()).isEqualByComparingTo("109.00");
        assertThat(lineFor(bill, "US").getAmount()).isEqualByComparingTo("45.00");
    }

    @Test
    @DisplayName("many CUR rows for one geography fold into a single invoice line")
    void aggregatesDailyRowsIntoOneLine() {
        withRates(rate("EU", "Bandwidth", "GB", "0.050"));

        List<CurAggregates.SvcUsageEntry> rows = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            rows.add(usage("EU-DataTransfer-Out-Bytes", 0.85, 100.0, "GB"));
        }

        CloudFrontBill bill = service.price(rows, CLIENT);

        assertThat(bill.getLines()).hasSize(1);
        assertThat(bill.getLines().get(0).getQuantity()).isEqualByComparingTo("3000.0000");
        assertThat(bill.getLines().get(0).getAmount()).isEqualByComparingTo("150.00");
    }

    // ── Bridging the PDF/CUR vocabulary gap ──────────────────────────────────

    /**
     * Existing rates were entered for the PDF flow, where AWS labels egress "Bandwidth".
     * CUR calls the same thing "DataTransfer-Out-Bytes". Without the alias, every existing
     * rate would silently fail to match and fall back to AWS cost.
     */
    @Test
    void bandwidthMatcherPricesCurDataTransferRows() {
        withRates(rate("US", "Bandwidth", "GB", "0.060"));

        CloudFrontBill bill = service.price(
                List.of(usage("US-DataTransfer-Out-Bytes", 5.0, 100.0, "GB")), CLIENT);

        assertThat(bill.getLines().get(0).isPriced()).isTrue();
        assertThat(bill.getLines().get(0).getAmount()).isEqualByComparingTo("6.00");
    }

    /**
     * Region matchers were also written in AWS display-name form. "Asia Pacific (Mumbai)"
     * is billed under CloudFront's India geography, which CUR reports as the "IN" prefix.
     */
    @Test
    void legacyAwsRegionNameMatchesCurGeography() {
        withRates(rate("Asia Pacific (Mumbai)", "Bandwidth", "GB", "0.109"));

        CloudFrontBill bill = service.price(
                List.of(usage("IN-DataTransfer-Out-Bytes", 17.0, 100.0, "GB")), CLIENT);

        assertThat(bill.getLines().get(0).isPriced()).isTrue();
        assertThat(bill.getLines().get(0).getAmount()).isEqualByComparingTo("10.90");
    }

    @Test
    void geographyDisplayNameAlsoMatches() {
        withRates(rate("India", "Bandwidth", "GB", "0.100"));

        CloudFrontBill bill = service.price(
                List.of(usage("IN-DataTransfer-Out-Bytes", 17.0, 50.0, "GB")), CLIENT);

        assertThat(bill.getLines().get(0).getAmount()).isEqualByComparingTo("5.00");
    }

    @Test
    @DisplayName("a geography-specific rate beats a wildcard")
    void prefersSpecificGeographyOverWildcard() {
        withRates(
                rate("ALL", "Bandwidth", "GB", "0.085"),
                rate("IN", "Bandwidth", "GB", "0.040"));

        CloudFrontBill bill = service.price(
                List.of(usage("IN-DataTransfer-Out-Bytes", 10.0, 100.0, "GB")), CLIENT);

        assertThat(bill.getLines().get(0).getRatePerUnit()).isEqualByComparingTo("0.040");
    }

    @Test
    void wildcardRateCoversGeographiesWithoutTheirOwnRate() {
        withRates(rate("ALL", "Bandwidth", "GB", "0.075"));

        CloudFrontBill bill = service.price(
                List.of(usage("SA-DataTransfer-Out-Bytes", 25.0, 200.0, "GB")), CLIENT);

        assertThat(bill.getLines().get(0).isPriced()).isTrue();
        assertThat(bill.getLines().get(0).getAmount()).isEqualByComparingTo("15.00");
    }

    // ── Requests and the per-block divisor ───────────────────────────────────

    /**
     * CloudFront request pricing is conventionally quoted per 10,000 requests. Multiplying
     * a raw request count by such a rate would overcharge by four orders of magnitude.
     */
    @Test
    void honoursPerTenThousandRequestsRate() {
        withRates(rate("US", "Requests-Tier2", "Per 10000 Requests", "0.0100"));

        CloudFrontBill bill = service.price(
                List.of(usage("US-Requests-Tier2-HTTPS", 0.24, 200000.0, "Requests")), CLIENT);

        CloudFrontBillLine line = bill.getLines().get(0);
        // 200,000 requests = 20 blocks of 10,000, at $0.01 => $0.20 (not $2,000).
        assertThat(line.getBillableUnits()).isEqualByComparingTo("20.0000");
        assertThat(line.getAmount()).isEqualByComparingTo("0.20");
    }

    @Test
    @DisplayName("data transfer and requests for one geography are priced independently")
    void separatesUsageClassesWithinAGeography() {
        withRates(
                rate("US", "Bandwidth", "GB", "0.045"),
                rate("US", "Requests", "Per 10000 Requests", "0.0075"));

        CloudFrontBill bill = service.price(List.of(
                usage("US-DataTransfer-Out-Bytes", 8.50, 1000.0, "GB"),
                usage("US-Requests-Tier2-HTTPS", 0.24, 200000.0, "Requests")), CLIENT);

        assertThat(bill.getLines()).hasSize(2);
        assertThat(classLine(bill, "DATA_TRANSFER_OUT").getAmount()).isEqualByComparingTo("45.00");
        assertThat(classLine(bill, "REQUESTS").getAmount()).isEqualByComparingTo("0.15");
    }

    // ── Safety: never silently misprice ──────────────────────────────────────

    @Test
    @DisplayName("an unmatched geography passes through at AWS cost and is flagged, not billed at zero")
    void unpricedGeographyFallsBackToCostAndIsFlagged() {
        withRates(rate("US", "Bandwidth", "GB", "0.045"));

        CloudFrontBill bill = service.price(List.of(
                usage("US-DataTransfer-Out-Bytes", 8.50, 1000.0, "GB"),
                usage("SA-DataTransfer-Out-Bytes", 25.00, 500.0, "GB")), CLIENT);

        CloudFrontBillLine sa = lineFor(bill, "SA");
        assertThat(sa.isPriced()).isFalse();
        assertThat(sa.getAmount()).isEqualByComparingTo("25.00");
        assertThat(sa.getNote()).contains("No private rate configured");
        assertThat(bill.hasUnpricedLines()).isTrue();
    }

    @Test
    @DisplayName("a per-GB rate is refused for a requests line rather than applied")
    void refusesRateWhoseUnitContradictsTheUsage() {
        // Operator error: a GB unit saved against a requests matcher.
        withRates(rate("US", "Requests-Tier2", "GB", "0.045"));

        CloudFrontBill bill = service.price(
                List.of(usage("US-Requests-Tier2-HTTPS", 0.24, 20000.0, "Requests")), CLIENT);

        // Applying $0.045/GB to 20,000 requests would bill $900 instead of $0.24.
        assertThat(bill.getLines().get(0).isPriced()).isFalse();
        assertThat(bill.getLines().get(0).getAmount()).isEqualByComparingTo("0.24");
    }

    @Test
    void noRatesConfiguredPassesEverythingThrough() {
        CloudFrontBill bill = service.price(
                List.of(usage("US-DataTransfer-Out-Bytes", 8.50, 1000.0, "GB")), CLIENT);

        assertThat(bill.getLines().get(0).isPriced()).isFalse();
        assertThat(bill.getTotalAmount()).isEqualByComparingTo("8.50");
    }

    /**
     * Free-tier CloudFront transfer arrives with real GB and zero AWS cost. Under per-GB
     * resale that quantity is still billable, so it must be priced rather than skipped.
     */
    @Test
    void billsFreeTierQuantityThatCostAwsNothing() {
        withRates(rate("US", "Bandwidth", "GB", "0.045"));

        CloudFrontBill bill = service.price(
                List.of(usage("US-DataTransfer-Out-Bytes", 0.0, 500.0, "GB")), CLIENT);

        assertThat(bill.getLines().get(0).getAmount()).isEqualByComparingTo("22.50");
        assertThat(bill.getMargin()).isEqualByComparingTo("22.50");
    }

    // ── Invoice presentation ─────────────────────────────────────────────────

    @Test
    @DisplayName("the invoice line shows quantity and rate, not just a total")
    void displayDescriptionShowsRateAndQuantity() {
        withRates(rate("IN", "Bandwidth", "GB", "0.017"));

        CloudFrontBill bill = service.price(
                List.of(usage("IN-DataTransfer-Out-Bytes", 21.00, 1240.5, "GB")), CLIENT);

        assertThat(bill.getLines().get(0).getDisplayDescription())
                .isEqualTo("India — Data transfer out: 1,240.50 GB @ $0.017 / GB");
    }

    @Test
    @DisplayName("a per-block rate is shown in its own units, not as a per-request price")
    void displayDescriptionShowsRateUnitForBlockPricing() {
        withRates(rate("US", "Requests", "Per 10000 Requests", "0.0075"));

        CloudFrontBill bill = service.price(
                List.of(usage("US-Requests-Tier2-HTTPS", 0.24, 200000.0, "Requests")), CLIENT);

        assertThat(bill.getLines().get(0).getDisplayDescription())
                .isEqualTo("United States — Requests: 200,000.00 Requests @ $0.0075 / Per 10000 Requests");
    }

    @Test
    @DisplayName("an unpriced line never implies a rate that was not applied")
    void displayDescriptionMarksCostPassthrough() {
        CloudFrontBill bill = service.price(
                List.of(usage("SA-DataTransfer-Out-Bytes", 25.00, 500.0, "GB")), CLIENT);

        assertThat(bill.getLines().get(0).getDisplayDescription())
                .isEqualTo("South America — Data transfer out: 500.00 GB (at AWS cost)")
                .doesNotContain("@");
    }

    // ── Unit helpers ─────────────────────────────────────────────────────────

    @Test
    void unitDivisorReadsPerBlockQuotes() {
        assertThat(CloudFrontPricingService.unitDivisor("Per 10000 Requests"))
                .isEqualByComparingTo("10000");
        assertThat(CloudFrontPricingService.unitDivisor("per 1,000 requests"))
                .isEqualByComparingTo("1000");
        assertThat(CloudFrontPricingService.unitDivisor("GB")).isEqualByComparingTo("1");
        assertThat(CloudFrontPricingService.unitDivisor(null)).isEqualByComparingTo("1");
        // A malformed quote must not silently scale the bill.
        assertThat(CloudFrontPricingService.unitDivisor("per requests")).isEqualByComparingTo("1");
    }

    @Test
    void unitConsistencyRejectsCrossKindRates() {
        assertThat(CloudFrontPricingService.unitIsConsistent(
                "GB", CloudFrontUsageParser.UsageClass.DATA_TRANSFER_OUT)).isTrue();
        assertThat(CloudFrontPricingService.unitIsConsistent(
                "Per 10000 Requests", CloudFrontUsageParser.UsageClass.DATA_TRANSFER_OUT)).isFalse();
        assertThat(CloudFrontPricingService.unitIsConsistent(
                "Per 10000 Requests", CloudFrontUsageParser.UsageClass.REQUESTS)).isTrue();
        assertThat(CloudFrontPricingService.unitIsConsistent(
                "GB", CloudFrontUsageParser.UsageClass.REQUESTS)).isFalse();
    }

    // ── Input handling ───────────────────────────────────────────────────────

    @Test
    void ignoresNonCloudFrontUsage() {
        withRates(rate("US", "Bandwidth", "GB", "0.045"));

        CloudFrontBill bill = service.price(List.of(
                new CurAggregates.SvcUsageEntry("Amazon Elastic Compute Cloud", "us-east-1",
                        "BoxUsage:t3.micro", 100.0, 720.0, "Hrs")), CLIENT);

        assertThat(bill.isEmpty()).isTrue();
    }

    @Test
    void handlesEmptyAndNullInput() {
        assertThat(service.price(List.of(), CLIENT).isEmpty()).isTrue();
        assertThat(service.price(null, CLIENT).isEmpty()).isTrue();
        assertThat(service.price(List.of(usage("US-DataTransfer-Out-Bytes", 1.0, 1.0, "GB")), null)
                .isEmpty()).isTrue();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private CloudFrontBillLine lineFor(CloudFrontBill bill, String geoCode) {
        return bill.getLines().stream()
                .filter(l -> geoCode.equals(l.getGeographyCode()))
                .findFirst().orElseThrow(() -> new AssertionError("no line for " + geoCode));
    }

    private CloudFrontBillLine classLine(CloudFrontBill bill, String usageClass) {
        return bill.getLines().stream()
                .filter(l -> usageClass.equals(l.getUsageClass()))
                .findFirst().orElseThrow(() -> new AssertionError("no line for " + usageClass));
    }
}
