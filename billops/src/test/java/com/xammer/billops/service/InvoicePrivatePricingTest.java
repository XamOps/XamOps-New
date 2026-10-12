package com.xammer.billops.service;

import com.xammer.billops.dto.InvoiceDto;
import com.xammer.billops.dto.PrivatePricingRequestDto;
import com.xammer.billops.repository.AppUserRepository;
import com.xammer.billops.repository.ClientRepository;
import com.xammer.billops.repository.CloudAccountRepository;
import com.xammer.billops.repository.CloudFrontPrivateRateRepository;
import com.xammer.billops.repository.DiscountRepository;
import com.xammer.billops.repository.InvoicePrivatePricingRepository;
import com.xammer.billops.repository.InvoiceRepository;
import com.xammer.billops.service.cdn.CloudFrontPricingService;
import com.xammer.billops.service.cur.CurInvoiceDataService;
import com.xammer.cloud.domain.Invoice;
import com.xammer.cloud.domain.InvoiceLineItem;
import com.xammer.cloud.domain.InvoicePrivatePricing;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Manual private pricing on an invoice, and the promise it makes to the overall discount.
 *
 * <p>The feature exists because some services are resold at a negotiated per-unit rate
 * rather than at a margin over provider cost. Two things follow, and both are money:
 * the charge becomes {@code quantity x rate}, and the invoice's overall discount must not
 * then reduce that rate, because the margin is already inside it. Conceding it twice is
 * invisible on the invoice -- the total is simply lower than it should be -- so it is
 * pinned here.
 */
class InvoicePrivatePricingTest {

    private static final long INVOICE_ID = 1L;
    private static final String EC2 = "Amazon Elastic Compute Cloud";
    private static final String CLOUDFRONT = "Amazon CloudFront";

    private InvoiceRepository invoiceRepository;
    private InvoiceManagementService service;
    private Invoice invoice;

    @BeforeEach
    void setUp() {
        invoiceRepository = mock(InvoiceRepository.class);

        service = new InvoiceManagementService(
                invoiceRepository,
                mock(CloudAccountRepository.class),
                mock(DiscountRepository.class),
                mock(BillingService.class),
                mock(GcpCostService.class),
                mock(AzureCostService.class),
                mock(AppUserRepository.class),
                mock(EmailService.class),
                mock(CloudFrontUsageService.class),
                mock(CloudFrontPrivateRateRepository.class),
                mock(InvoicePrivatePricingRepository.class),
                mock(CloudFrontPricingService.class),
                mock(ClientRepository.class),
                mock(RedisCacheService.class),
                mock(AccountAccessHelper.class),
                mock(CurInvoiceDataService.class));

        // $100 of EC2 and $50 of CloudFront, at provider cost.
        invoice = new Invoice();
        invoice.setId(INVOICE_ID);
        invoice.setStatus(Invoice.InvoiceStatus.DRAFT);
        invoice.setLineItems(new ArrayList<>(List.of(
                line(invoice, EC2, "us-east-1", "BoxUsage:m7i.xlarge", "1000", "Hrs", "100.0000"),
                line(invoice, CLOUDFRONT, "", "DataTransfer-Out-Bytes", "1000", "GB", "50.0000"))));
        invoice.setDiscounts(new ArrayList<>());
        invoice.setPrivatePricings(new ArrayList<>());

        when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.of(invoice));
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static InvoiceLineItem line(Invoice invoice, String service, String region,
                                        String usageType, String quantity, String unit, String cost) {
        InvoiceLineItem item = new InvoiceLineItem();
        item.setInvoice(invoice);
        item.setServiceName(service);
        item.setRegionName(region);
        item.setUsageType(usageType);
        item.setResourceName(usageType);
        item.setUsageQuantity(quantity);
        item.setUnit(unit);
        item.setCost(new BigDecimal(cost));
        return item;
    }

    private static PrivatePricingRequestDto request(String service, String rate, String rateUnit, boolean shield) {
        PrivatePricingRequestDto dto = new PrivatePricingRequestDto();
        dto.setServiceName(service);
        dto.setUnitRate(new BigDecimal(rate));
        dto.setRateUnit(rateUnit);
        dto.setExcludeFromOverallDiscount(shield);
        return dto;
    }

    private InvoiceLineItem lineFor(String service) {
        return invoice.getLineItems().stream()
                .filter(l -> service.equals(l.getServiceName()))
                .findFirst().orElseThrow();
    }

    // ── The multiply ────────────────────────────────────────────────────────

    @Test
    @DisplayName("the charge becomes quantity x rate, and the provider's figure is kept")
    void chargeBecomesQuantityTimesRate() {
        service.applyPrivatePricing(INVOICE_ID, request(CLOUDFRONT, "0.085", "GB", true));

        InvoiceLineItem cf = lineFor(CLOUDFRONT);
        // 1,000 GB at $0.085
        assertThat(cf.getCost()).isEqualByComparingTo("85.0000");
        assertThat(cf.getPrivateUnitRate()).isEqualByComparingTo("0.085");
        // Kept, so removal can restore it and the margin has something to measure against.
        assertThat(cf.getOriginalCost()).isEqualByComparingTo("50.0000");

        // Untouched: pricing is scoped, not global.
        assertThat(lineFor(EC2).getCost()).isEqualByComparingTo("100.0000");
        assertThat(lineFor(EC2).isPrivatelyPriced()).isFalse();

        // The invoice total follows from the line, with no separate bookkeeping.
        assertThat(invoice.getPreDiscountTotal()).isEqualByComparingTo("185.0000");
        assertThat(invoice.getPrivatePricedTotal()).isEqualByComparingTo("85.0000");
        assertThat(invoice.getDiscountShieldedTotal()).isEqualByComparingTo("85.0000");
    }

    @Test
    @DisplayName("removing a rate restores the provider's figure exactly")
    void removingARateRestoresProviderCost() {
        service.applyPrivatePricing(INVOICE_ID, request(CLOUDFRONT, "0.085", "GB", true));
        invoice.getPrivatePricings().get(0).setId(7L);

        service.removePrivatePricing(INVOICE_ID, 7L);

        InvoiceLineItem cf = lineFor(CLOUDFRONT);
        assertThat(cf.getCost()).isEqualByComparingTo("50.0000");
        assertThat(cf.getPrivateUnitRate()).isNull();
        assertThat(cf.getOriginalCost()).isNull();
        assertThat(invoice.getPreDiscountTotal()).isEqualByComparingTo("150.0000");
        assertThat(invoice.getPrivatePricedTotal()).isEqualByComparingTo("0.0000");
        assertThat(invoice.getPrivatePricings()).isEmpty();
    }

    // ── The shield ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("an overall discount skips the privately priced charges")
    void overallDiscountSkipsShieldedCharges() {
        service.applyPrivatePricing(INVOICE_ID, request(CLOUDFRONT, "0.085", "GB", true));
        service.applyDiscountToInvoice(INVOICE_ID, "ALL", new BigDecimal("10"));

        // Charges are $185 ($100 EC2 + $85 CloudFront at the private rate), but only the
        // $100 is discountable: 10% of that is $10, not the $18.50 an unshielded invoice
        // would concede.
        assertThat(invoice.getDiscountAmount()).isEqualByComparingTo("10.0000");

        BigDecimal taxable = invoice.getPreDiscountTotal().subtract(invoice.getDiscountAmount());
        assertThat(taxable).isEqualByComparingTo("175.0000");
        assertThat(invoice.getTaxAmount()).isEqualByComparingTo("31.5000");
        assertThat(invoice.getAmount()).isEqualByComparingTo("206.5000");
    }

    @Test
    @DisplayName("a consumption-wide discount skips them too")
    void consumptionScopedDiscountSkipsShieldedCharges() {
        // Shield EC2 rather than CloudFront: CloudFront is already outside the consumption
        // base, so shielding it would prove nothing about this scope.
        service.applyPrivatePricing(INVOICE_ID, request(EC2, "0.15", "Hrs", true));
        service.applyDiscountToInvoice(INVOICE_ID, "AWS Consumption Charge", new BigDecimal("10"));

        // EC2 is the only non-CloudFront charge and it is shielded, so nothing is left to
        // discount.
        assertThat(lineFor(EC2).getCost()).isEqualByComparingTo("150.0000");
        assertThat(invoice.getDiscountAmount()).isEqualByComparingTo("0.0000");
    }

    @Test
    @DisplayName("with the checkbox cleared, the overall discount applies as usual")
    void unshieldedPricingIsStillDiscountable() {
        service.applyPrivatePricing(INVOICE_ID, request(CLOUDFRONT, "0.085", "GB", false));
        service.applyDiscountToInvoice(INVOICE_ID, "ALL", new BigDecimal("10"));

        // 10% of the whole $185.
        assertThat(invoice.getDiscountAmount()).isEqualByComparingTo("18.5000");
        assertThat(invoice.getDiscountShieldedTotal()).isEqualByComparingTo("0.0000");
    }

    @Test
    @DisplayName("the shield can be lifted afterwards, and the total follows")
    void shieldCanBeToggled() {
        service.applyPrivatePricing(INVOICE_ID, request(CLOUDFRONT, "0.085", "GB", true));
        invoice.getPrivatePricings().get(0).setId(7L);
        service.applyDiscountToInvoice(INVOICE_ID, "ALL", new BigDecimal("10"));
        assertThat(invoice.getDiscountAmount()).isEqualByComparingTo("10.0000");

        service.setPrivatePricingDiscountExclusion(INVOICE_ID, 7L, false);

        assertThat(invoice.getDiscountAmount()).isEqualByComparingTo("18.5000");
        assertThat(invoice.getPrivatePricings().get(0).isExcludeFromOverallDiscount()).isFalse();
    }

    @Test
    @DisplayName("a discount naming the priced service by name still applies to it")
    void aDiscountNamingTheServiceStillApplies() {
        service.applyPrivatePricing(INVOICE_ID, request(CLOUDFRONT, "0.085", "GB", true));
        service.applyDiscountToInvoice(INVOICE_ID, CLOUDFRONT, new BigDecimal("10"));

        // The shield is against discounts that sweep the invoice without naming what is
        // inside it. Picking this service deliberately is the admin saying they mean it --
        // 10% of the $85 now billed.
        assertThat(invoice.getDiscountAmount()).isEqualByComparingTo("8.5000");
    }

    // ── What the invoice refuses ────────────────────────────────────────────

    @Test
    @DisplayName("a credit inside the scope is left at provider cost")
    void creditsInsideTheScopeAreNotRepriced() {
        // A Savings Plan negation: positive quantity, negative amount. Repricing it would
        // turn money owed to the customer into money charged to them.
        invoice.getLineItems().add(
                line(invoice, EC2, "us-east-1", "BoxUsage:m7i.xlarge", "500", "Hrs", "-20.0000"));

        service.applyPrivatePricing(INVOICE_ID, request(EC2, "0.15", "Hrs", true));

        InvoiceLineItem negation = invoice.getLineItems().stream()
                .filter(l -> l.getCost().signum() < 0).findFirst().orElseThrow();
        assertThat(negation.getCost()).isEqualByComparingTo("-20.0000");
        assertThat(negation.isPrivatelyPriced()).isFalse();

        InvoicePrivatePricing applied = invoice.getPrivatePricings().get(0);
        assertThat(applied.getLineCount()).isEqualTo(1);
        assertThat(applied.getSkippedLineCount()).isEqualTo(1);

        // The credit is still at provider cost, so it stays discountable.
        assertThat(invoice.getDiscountShieldedTotal()).isEqualByComparingTo("150.0000");
    }

    @Test
    @DisplayName("one rate across several units is refused rather than applied to all of them")
    void mixedUnitsAreRefused() {
        // CloudFront bills egress in GB and requests in counts. A per-GB rate on the
        // request line would be wrong by orders of magnitude and still look plausible.
        invoice.getLineItems().add(
                line(invoice, CLOUDFRONT, "", "Requests-Tier1", "2000000", "Requests", "2.0000"));

        assertThatThrownBy(() -> service.applyPrivatePricing(INVOICE_ID, request(CLOUDFRONT, "0.085", "GB", true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("more than one unit")
                .hasMessageContaining("per usage type");

        // Nothing moved.
        assertThat(lineFor(CLOUDFRONT).getCost()).isEqualByComparingTo("50.0000");
        assertThat(invoice.getPrivatePricings()).isEmpty();
    }

    @Test
    @DisplayName("narrowing to a usage type is how mixed units get priced")
    void narrowingToAUsageTypePricesEachUnitSeparately() {
        invoice.getLineItems().add(
                line(invoice, CLOUDFRONT, "", "Requests-Tier1", "2000000", "Requests", "2.0000"));

        PrivatePricingRequestDto egress = request(CLOUDFRONT, "0.085", "GB", true);
        egress.setUsageType("DataTransfer-Out-Bytes");
        service.applyPrivatePricing(INVOICE_ID, egress);

        PrivatePricingRequestDto requests = request(CLOUDFRONT, "0.01", "Per 10000 Requests", true);
        requests.setUsageType("Requests-Tier1");
        service.applyPrivatePricing(INVOICE_ID, requests);

        assertThat(lineFor(CLOUDFRONT).getCost()).isEqualByComparingTo("85.0000");
        // 2,000,000 requests at $0.01 per 10,000 = $2.00
        assertThat(invoice.getLineItems().stream()
                .filter(l -> "Requests-Tier1".equals(l.getUsageType()))
                .findFirst().orElseThrow().getCost()).isEqualByComparingTo("2.0000");
        assertThat(invoice.getPrivatePricings()).hasSize(2);
    }

    @Test
    @DisplayName("a scope that overlaps an applied rate is refused, naming the one it collides with")
    void overlappingScopesAreRefused() {
        service.applyPrivatePricing(INVOICE_ID, request(CLOUDFRONT, "0.085", "GB", true));

        PrivatePricingRequestDto narrower = request(CLOUDFRONT, "0.09", "GB", true);
        narrower.setUsageType("DataTransfer-Out-Bytes");

        // Two rates with a claim on one line would make the result depend on the order
        // they were entered, and removal ambiguous.
        assertThatThrownBy(() -> service.applyPrivatePricing(INVOICE_ID, narrower))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already priced");

        assertThat(invoice.getPrivatePricings()).hasSize(1);
        assertThat(lineFor(CLOUDFRONT).getCost()).isEqualByComparingTo("85.0000");
    }

    @Test
    void aScopeMatchingNothingIsRefused() {
        assertThatThrownBy(() -> service.applyPrivatePricing(
                INVOICE_ID, request("Amazon Route 53", "0.5", "Queries", true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No charges on this invoice match");
    }

    @Test
    void aMissingOrNegativeRateIsRefused() {
        PrivatePricingRequestDto noRate = new PrivatePricingRequestDto();
        noRate.setServiceName(CLOUDFRONT);
        assertThatThrownBy(() -> service.applyPrivatePricing(INVOICE_ID, noRate))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> service.applyPrivatePricing(
                INVOICE_ID, request(CLOUDFRONT, "-0.085", "GB", true)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a finalized invoice cannot be repriced")
    void finalizedInvoicesAreClosedToPricing() {
        invoice.setStatus(Invoice.InvoiceStatus.FINALIZED);

        assertThatThrownBy(() -> service.applyPrivatePricing(
                INVOICE_ID, request(CLOUDFRONT, "0.085", "GB", true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("finalized");
    }

    @Test
    @DisplayName("a scope with no usable usage is refused, with the reason")
    void aScopeWithNoUsableUsageIsRefused() {
        // The region-only fallback line: quantity "1", unit "N/A", a flat charge no
        // per-unit rate can express.
        invoice.setLineItems(new ArrayList<>(List.of(
                line(invoice, "AWS Config", "us-east-1", null, "0", "N/A", "12.0000"))));

        assertThatThrownBy(() -> service.applyPrivatePricing(
                INVOICE_ID, request("AWS Config", "0.5", null, true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no measured usage");
    }

    // ── What the customer's detail bill says ────────────────────────────────
    //
    // A charge built from the Cost and Usage Report describes itself with the provider's
    // own rate. Once an admin reprices the line, printing that description beside the new
    // amount puts a contradiction on the bill -- the rate times the quantity does not
    // equal the total beside it -- and the first person to check the arithmetic finds it.

    @Test
    @DisplayName("the detail bill prints the rate billed, not the provider's")
    void detailBillPrintsTheRateActuallyBilled() {
        // 1,422 Hrs described by CUR as "$0.056 per NAT Gateway Hour", repriced to $0.044.
        invoice.setLineItems(new ArrayList<>(List.of(
                line(invoice, EC2, "ap-south-1", "APS3-NatGateway-Hours",
                        "1422", "Hrs", "79.6320"))));
        invoice.getLineItems().get(0).setResourceName("$0.056 per NAT Gateway Hour");

        service.applyPrivatePricing(INVOICE_ID, request(EC2, "0.044", "Hrs", true));

        InvoiceDto.LineItemDto printed = InvoiceDto.fromEntity(invoice).getLineItems().get(0);
        String description = InvoiceManagementService.detailLineDescription(printed);

        // The provider's rate is gone and ours is in its place; the wording that says what
        // is being billed survives.
        assertThat(description).isEqualTo("NAT Gateway Hour @ $0.044 per Hrs");
        assertThat(description).doesNotContain("0.056");

        // The invariant that makes the bill checkable: printed rate x printed quantity is
        // the printed amount.
        BigDecimal quantity = new BigDecimal(printed.getUsageQuantity());
        assertThat(new BigDecimal("0.044").multiply(quantity))
                .isEqualByComparingTo(printed.getCost());
    }

    @Test
    @DisplayName("a rate quoted per 10,000 keeps its divisor on the bill")
    void detailBillKeepsTheQuotedDivisor() {
        invoice.setLineItems(new ArrayList<>(List.of(
                line(invoice, CLOUDFRONT, "", "Requests-Tier1", "2000000", "Requests", "2.0000"))));
        invoice.getLineItems().get(0).setResourceName("$0.0000010 per Request");

        service.applyPrivatePricing(INVOICE_ID, request(CLOUDFRONT, "0.01", "Per 10000 Requests", true));

        InvoiceDto.LineItemDto printed = InvoiceDto.fromEntity(invoice).getLineItems().get(0);
        String description = InvoiceManagementService.detailLineDescription(printed);

        // The quoted phrase is printed as the admin wrote it, not re-prefixed, and not
        // flattened to the line's own "Requests" unit -- which would read as a rate four
        // orders of magnitude higher than the one charged.
        assertThat(description).isEqualTo("Request @ $0.01 Per 10000 Requests");
        assertThat(description).doesNotContain("per Per");

        // 2,000,000 / 10,000 x $0.01 = $2.00, which is what the amount column says.
        BigDecimal quantity = new BigDecimal(printed.getUsageQuantity());
        assertThat(new BigDecimal("0.01").multiply(quantity).divide(new BigDecimal("10000")))
                .isEqualByComparingTo(printed.getCost());
    }

    @Test
    @DisplayName("a line left at provider cost keeps its description untouched")
    void detailBillLeavesUnpricedLinesAlone() {
        invoice.getLineItems().get(0).setResourceName("$0.2121 per On Demand Linux m7i.xlarge Instance Hour");

        InvoiceDto.LineItemDto untouched = InvoiceDto.fromEntity(invoice).getLineItems().stream()
                .filter(l -> EC2.equals(l.getServiceName())).findFirst().orElseThrow();

        assertThat(InvoiceManagementService.detailLineDescription(untouched))
                .isEqualTo("$0.2121 per On Demand Linux m7i.xlarge Instance Hour");
    }

    @Test
    @DisplayName("a description carrying no provider rate keeps all of its wording")
    void detailBillAppendsToDescriptionsWithoutARate() {
        // The Cost Explorer path names a resource rather than quoting a rate.
        invoice.setLineItems(new ArrayList<>(List.of(
                line(invoice, EC2, "ap-south-1", null, "720", "Hrs", "50.0000"))));
        invoice.getLineItems().get(0).setResourceName("i-0abc123def456 (m5.large)");

        service.applyPrivatePricing(INVOICE_ID, request(EC2, "0.08", "Hrs", true));

        InvoiceDto.LineItemDto printed = InvoiceDto.fromEntity(invoice).getLineItems().get(0);
        assertThat(InvoiceManagementService.detailLineDescription(printed))
                .isEqualTo("i-0abc123def456 (m5.large) @ $0.08 per Hrs");
    }

    @Test
    @DisplayName("changing a rate changes what the bill prints")
    void rePricingChangesWhatTheBillPrints() {
        invoice.setLineItems(new ArrayList<>(List.of(
                line(invoice, EC2, "ap-south-1", "APS3-NatGateway-Hours", "1422", "Hrs", "79.6320"))));
        invoice.getLineItems().get(0).setResourceName("$0.056 per NAT Gateway Hour");

        service.applyPrivatePricing(INVOICE_ID, request(EC2, "0.044", "Hrs", true));
        invoice.getPrivatePricings().get(0).setId(7L);
        assertThat(InvoiceManagementService.detailLineDescription(
                InvoiceDto.fromEntity(invoice).getLineItems().get(0)))
                .contains("$0.044");

        // Changing a rate is removing it and setting another: the provider's figure is
        // restored first, so the second rate is not applied on top of the first.
        service.removePrivatePricing(INVOICE_ID, 7L);
        service.applyPrivatePricing(INVOICE_ID, request(EC2, "0.05", "Hrs", true));

        InvoiceDto.LineItemDto printed = InvoiceDto.fromEntity(invoice).getLineItems().get(0);
        assertThat(InvoiceManagementService.detailLineDescription(printed))
                .isEqualTo("NAT Gateway Hour @ $0.05 per Hrs");
        // 1,422 x $0.05, from the provider's quantity -- not from the $0.044 amount.
        assertThat(printed.getCost()).isEqualByComparingTo("71.1000");
    }

    @Test
    @DisplayName("a rate stored without a quoted unit prints against the line's own unit")
    void aRateWithNoQuotedUnitFallsBackToTheLineUnit() {
        // A rate applied before the quoted unit was recorded. No quoted unit means no
        // divisor was applied, so the rate is per one of the line's units -- printing the
        // line's unit is what the arithmetic actually did.
        invoice.setLineItems(new ArrayList<>(List.of(
                line(invoice, EC2, "ap-south-1", "APS3-NatGateway-Hours", "1422", "Hrs", "62.5680"))));
        InvoiceLineItem item = invoice.getLineItems().get(0);
        item.setResourceName("$0.056 per NAT Gateway Hour");
        item.setPrivateUnitRate(new BigDecimal("0.044"));
        item.setPrivateRateUnit(null);
        item.setOriginalCost(new BigDecimal("79.6320"));

        InvoiceDto.LineItemDto printed = InvoiceDto.fromEntity(invoice).getLineItems().get(0);
        assertThat(InvoiceManagementService.detailLineDescription(printed))
                .isEqualTo("NAT Gateway Hour @ $0.044 per Hrs");
    }

    @Test
    @DisplayName("a placeholder unit is not printed as if it were one")
    void aPlaceholderLineUnitIsNotPrintedAsAUnit() {
        invoice.setLineItems(new ArrayList<>(List.of(
                line(invoice, "AWS Config", "ap-south-1", null, "100", "N/A", "50.0000"))));
        InvoiceLineItem item = invoice.getLineItems().get(0);
        item.setResourceName("AWS Config Charges");
        item.setPrivateUnitRate(new BigDecimal("0.5"));
        item.setPrivateRateUnit(null);

        InvoiceDto.LineItemDto printed = InvoiceDto.fromEntity(invoice).getLineItems().get(0);
        assertThat(InvoiceManagementService.detailLineDescription(printed))
                .isEqualTo("AWS Config Charges @ $0.5 per unit");
    }

    @Test
    void ratePhraseDoesNotDoubleThePerPrefix() {
        assertThat(InvoiceManagementService.ratePhrase(new BigDecimal("0.085"), "GB"))
                .isEqualTo("$0.085 per GB");
        assertThat(InvoiceManagementService.ratePhrase(new BigDecimal("0.01"), "Per 10000 Requests"))
                .isEqualTo("$0.01 Per 10000 Requests");
        assertThat(InvoiceManagementService.ratePhrase(new BigDecimal("0.5"), null))
                .isEqualTo("$0.5 per unit");
        // A rate's own precision survives: rounding it for display would make the printed
        // arithmetic disagree with the amount.
        assertThat(InvoiceManagementService.ratePhrase(new BigDecimal("0.0000012500"), "GB"))
                .isEqualTo("$0.00000125 per GB");
    }

    // ── What the page and the PDF are told ──────────────────────────────────

    @Test
    @DisplayName("the shield decision reaches the DTO per line, for the PDF and the page")
    void theDtoCarriesTheShieldDecisionPerLine() {
        service.applyPrivatePricing(INVOICE_ID, request(CLOUDFRONT, "0.085", "GB", true));

        InvoiceDto dto = InvoiceDto.fromEntity(invoice);

        InvoiceDto.LineItemDto cf = dto.getLineItems().stream()
                .filter(l -> CLOUDFRONT.equals(l.getServiceName())).findFirst().orElseThrow();
        assertThat(cf.isPrivatelyPriced()).isTrue();
        assertThat(cf.isDiscountShielded()).isTrue();
        assertThat(cf.getPrivateUnitRate()).isEqualByComparingTo("0.085");
        assertThat(cf.getOriginalCost()).isEqualByComparingTo("50.0000");

        InvoiceDto.LineItemDto ec2 = dto.getLineItems().stream()
                .filter(l -> EC2.equals(l.getServiceName())).findFirst().orElseThrow();
        assertThat(ec2.isDiscountShielded()).isFalse();

        assertThat(dto.getPrivatePricing()).hasSize(1);
        assertThat(dto.getPrivatePricing().get(0).getMargin()).isEqualByComparingTo("35.0000");
        assertThat(dto.getDiscountShieldedTotal()).isEqualByComparingTo("85.0000");
    }
}
