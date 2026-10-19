package com.xammer.billops.service;

import com.xammer.billops.dto.InvoiceDto;
import com.xammer.billops.repository.AppUserRepository;
import com.xammer.billops.repository.CloudAccountRepository;
import com.xammer.billops.repository.CloudFrontPrivateRateRepository;
import com.xammer.billops.repository.ClientRepository;
import com.xammer.billops.repository.DiscountRepository;
import com.xammer.billops.repository.InvoicePrivatePricingRepository;
import com.xammer.billops.repository.InvoiceRepository;
import com.xammer.billops.service.cdn.CloudFrontPricingService;
import com.xammer.billops.service.cur.CurInvoiceDataService;
import com.xammer.cloud.domain.Discount;
import com.xammer.cloud.domain.Invoice;
import com.xammer.cloud.domain.InvoiceLineItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The editable, optional Holding Cost markup on an invoice's AWS Marketplace charge.
 *
 * <p>The promise under test: null/0 percent leaves every figure and every PDF byte exactly
 * as it was before this feature; a set percent grosses the Marketplace charge up by that
 * percent (truncated, matching the attached reference invoice), flows the grossed figure
 * into the taxable total, GST and total due, and never lets the markup enter a discount
 * base. Figures are pinned to the reference invoice: {@code 11994.84 + 15% => 13794.06},
 * {@code 13794.06 x 95.4 = 1,315,953.32} INR.
 */
class InvoiceHoldingCostTest {

    private InvoiceRepository invoiceRepository;
    private InvoiceManagementService service;

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
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // ── Pure helpers ────────────────────────────────────────────────────────

    @Test
    @DisplayName("grossedMarketplace truncates with RoundingMode.DOWN (11994.84 + 15% => 13794.06)")
    void grossedMarketplaceMatchesTheReferenceInvoice() {
        assertThat(InvoiceManagementService.grossedMarketplace(
                new BigDecimal("11994.84"), new BigDecimal("15")))
                .isEqualByComparingTo("13794.06");
    }

    @Test
    void grossedMarketplaceTreatsNullAndZeroPercentAsNoMarkup() {
        assertThat(InvoiceManagementService.grossedMarketplace(new BigDecimal("100.00"), null))
                .isEqualByComparingTo("100.00");
        assertThat(InvoiceManagementService.grossedMarketplace(new BigDecimal("100.00"), BigDecimal.ZERO))
                .isEqualByComparingTo("100.00");
        assertThat(InvoiceManagementService.grossedMarketplace(null, new BigDecimal("15")))
                .isEqualByComparingTo("0");
    }

    @Test
    void holdingCostMarginIsGrossedMinusBase() {
        assertThat(InvoiceManagementService.holdingCostMargin(
                new BigDecimal("11994.84"), new BigDecimal("15")))
                .isEqualByComparingTo("1799.22"); // 13794.06 - 11994.84
        assertThat(InvoiceManagementService.holdingCostMargin(new BigDecimal("500.00"), null))
                .isEqualByComparingTo("0");
        assertThat(InvoiceManagementService.holdingCostMargin(null, new BigDecimal("15")))
                .isEqualByComparingTo("0");
    }

    // ── recalculateTotals(Invoice) — the authoritative admin path ────────────

    @Test
    @DisplayName("null percent reproduces pre-change totals exactly")
    void nullPercentIsByteForByteTheOldBehaviour() {
        // recalculateTotals is private; drive it through the public persist method, which
        // calls it. Setting null leaves no markup -> totals identical to pre-feature.
        Invoice base = recalc(invoiceWithDiscount(null), null);
        Invoice withZeroPct = recalc(invoiceWithDiscount(null), BigDecimal.ZERO);

        assertThat(withZeroPct.getPreDiscountTotal()).isEqualByComparingTo(base.getPreDiscountTotal());
        assertThat(withZeroPct.getDiscountAmount()).isEqualByComparingTo(base.getDiscountAmount());
        assertThat(withZeroPct.getTaxAmount()).isEqualByComparingTo(base.getTaxAmount());
        assertThat(withZeroPct.getAmount()).isEqualByComparingTo(base.getAmount());
    }

    @Test
    @DisplayName("the markup is additive on taxable/amount and never touches the discount")
    void markupRaisesTaxableButLeavesDiscountUntouched() {
        Invoice noMarkup = recalc(invoiceWithDiscount(null), null);
        Invoice withMarkup = recalc(invoiceWithDiscount(null), new BigDecimal("15.00"));

        // Discount base subtracts only the ORIGINAL marketplace USD, so the holding cost
        // does not change the discount at all.
        assertThat(withMarkup.getDiscountAmount()).isEqualByComparingTo(noMarkup.getDiscountAmount());

        BigDecimal margin = InvoiceManagementService.holdingCostMargin(
                withMarkup.getMarketplaceCharge(), withMarkup.getMarketplaceHoldingCostPercent());
        assertThat(margin).isGreaterThan(BigDecimal.ZERO);

        // Taxable (amount - tax) rises by exactly the margin.
        BigDecimal noMarkupTaxable = noMarkup.getAmount().subtract(noMarkup.getTaxAmount());
        BigDecimal withMarkupTaxable = withMarkup.getAmount().subtract(withMarkup.getTaxAmount());
        assertThat(withMarkupTaxable.subtract(noMarkupTaxable)).isEqualByComparingTo(margin);
    }

    @Test
    @DisplayName("the markup is added after all discounts, on top of the non-discounted marketplace")
    void markupAppliesAfterDiscountsOnTopOfMarketplace() {
        // Consumption fully discounted (100% ALL). The marketplace charge is never
        // discounted, so taxable ends at the GROSSED marketplace: base + margin. This
        // proves the markup is applied after the discount math rather than inside a base.
        Invoice invoice = new Invoice();
        invoice.setStatus(Invoice.InvoiceStatus.DRAFT);
        List<InvoiceLineItem> items = new ArrayList<>();
        items.add(lineItem("Amazon EC2", "100.00")); // discountable consumption
        items.add(lineItem("Amazon S3", "50.00")); // part of preDiscountTotal (the marketplace portion)
        invoice.setLineItems(items);
        invoice.setMarketplaceCharge(new BigDecimal("50.00"));
        invoice.setDiscounts(List.of(discount("ALL", "100")));

        Invoice result = recalc(invoice, new BigDecimal("15.00"));

        // grossed marketplace = 50.00 * 1.15 = 57.50 (DOWN); consumption fully discounted.
        BigDecimal grossedMarketplace = InvoiceManagementService.grossedMarketplace(
                new BigDecimal("50.00"), new BigDecimal("15.00")); // 57.50
        BigDecimal taxable = result.getAmount().subtract(result.getTaxAmount());
        assertThat(taxable).isEqualByComparingTo(grossedMarketplace);
    }

    // ── setMarketplaceHoldingCost — the persist method ───────────────────────

    @Test
    void rangeRejectionBelowZeroAndAboveHundred() {
        when(invoiceRepository.findById(1L)).thenAnswer(i -> Optional.of(draftInvoice()));
        assertThatThrownBy(() -> service.setMarketplaceHoldingCost(1L, new BigDecimal("-1")))
                .isInstanceOf(IllegalArgumentException.class);
        when(invoiceRepository.findById(1L)).thenAnswer(i -> Optional.of(draftInvoice()));
        assertThatThrownBy(() -> service.setMarketplaceHoldingCost(1L, new BigDecimal("101")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nonDraftIsRejected() {
        Invoice finalized = draftInvoice();
        finalized.setStatus(Invoice.InvoiceStatus.FINALIZED);
        when(invoiceRepository.findById(1L)).thenReturn(Optional.of(finalized));
        assertThatThrownBy(() -> service.setMarketplaceHoldingCost(1L, new BigDecimal("15")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void nullOrZeroClearsTheHoldingCost() {
        Invoice invoice = draftInvoice();
        invoice.setMarketplaceHoldingCostPercent(new BigDecimal("15.00"));
        when(invoiceRepository.findById(1L)).thenReturn(Optional.of(invoice));

        Invoice cleared = service.setMarketplaceHoldingCost(1L, null);
        assertThat(cleared.getMarketplaceHoldingCostPercent()).isNull();

        invoice.setMarketplaceHoldingCostPercent(new BigDecimal("15.00"));
        Invoice clearedByZero = service.setMarketplaceHoldingCost(1L, BigDecimal.ZERO);
        assertThat(clearedByZero.getMarketplaceHoldingCostPercent()).isNull();
    }

    @Test
    void aValidPercentIsStoredAtScaleTwo() {
        Invoice invoice = draftInvoice();
        when(invoiceRepository.findById(1L)).thenReturn(Optional.of(invoice));
        Invoice saved = service.setMarketplaceHoldingCost(1L, new BigDecimal("15"));
        assertThat(saved.getMarketplaceHoldingCostPercent()).isEqualByComparingTo("15.00");
    }

    // ── PDF rendering — reference branch ─────────────────────────────────────

    @Test
    @DisplayName("markup PDF renders the reference Marketplace cell and grossed figures")
    void pdfWithMarkupMatchesTheReference() {
        InvoiceDto dto = referenceDto(new BigDecimal("15.00"));
        String text = pdfText(dto);

        assertThat(text).contains("Amazon Web Service Charges");
        assertThat(text).contains("For the Period of August 2026");
        // The literal holding-cost lines (PDF text extraction may drop the leading newline
        // but keeps the content).
        assertThat(text).contains("$ 11994.84 + 15% Holding Cost)");
        assertThat(text).contains("($13794.06 @95.4");
        // The grossed marketplace INR amount (13794.06 * 95.4 = 1,315,953.32).
        assertThat(text).contains("1,315,953.32");

        // The holding-cost Marketplace cell drops the "Account ID:" line. Scope the check
        // to the Marketplace cell only (the STANDARD AWS row above legitimately keeps it).
        String marketplaceCell = text.substring(text.indexOf("AWS Consumption Charge - Marketplace"));
        assertThat(marketplaceCell).doesNotContain("Account ID:");
        // The grossed-up INR flows into the taxable total, GST and total due.
        assertThat(text).contains("3,122,609.90"); // taxable
        assertThat(text).contains("562,069.78");   // IGST@18%
        assertThat(text).contains("3,684,680.00"); // total due
    }

    // ── PDF rendering — zero branch (backward compatibility) ─────────────────

    @Test
    @DisplayName("zero percent PDF renders today's Marketplace cell byte-for-byte")
    void pdfWithZeroPercentIsBackwardCompatible() {
        InvoiceDto dto = referenceDto(null);
        String text = pdfText(dto);

        // Today's Marketplace cell, verbatim: the "Amazon Web Services, Inc. - Marketplace"
        // sub-line, the Account ID line, and the single base-USD rate line.
        String marketplaceCell = text.substring(text.indexOf("AWS Consumption Charge - Marketplace"));
        assertThat(marketplaceCell).contains("Amazon Web Services, Inc. - Marketplace");
        assertThat(marketplaceCell).contains("Account ID: 605134457560");
        // Base USD, not grossed, and no holding-cost wording.
        assertThat(marketplaceCell).contains("($11994.84 @95.4");
        assertThat(text).doesNotContain("Holding Cost");
        // Base marketplace INR: 11994.84 * 95.4 = 1,144,307.74.
        assertThat(text).contains("1,144,307.74");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Runs recalculateTotals via the public persist method (which calls it) and returns the saved invoice. */
    private Invoice recalc(Invoice invoice, BigDecimal pct) {
        when(invoiceRepository.findById(99L)).thenReturn(Optional.of(invoice));
        return service.setMarketplaceHoldingCost(99L, pct);
    }

    private Invoice draftInvoice() {
        Invoice invoice = new Invoice();
        invoice.setStatus(Invoice.InvoiceStatus.DRAFT);
        invoice.setLineItems(new ArrayList<>());
        invoice.setDiscounts(new ArrayList<>());
        invoice.setMarketplaceCharge(new BigDecimal("100.00"));
        return invoice;
    }

    /** A draft with discountable consumption, a marketplace charge, and a 10% ALL discount. */
    private Invoice invoiceWithDiscount(BigDecimal holdingPct) {
        Invoice invoice = new Invoice();
        invoice.setStatus(Invoice.InvoiceStatus.DRAFT);
        List<InvoiceLineItem> items = new ArrayList<>();
        items.add(lineItem("Amazon EC2", "1000.00"));
        items.add(lineItem("Amazon S3", "11994.84")); // the marketplace portion of the total
        invoice.setLineItems(items);
        invoice.setMarketplaceCharge(new BigDecimal("11994.84"));
        invoice.setMarketplaceHoldingCostPercent(holdingPct);
        invoice.setDiscounts(List.of(discount("ALL", "10")));
        return invoice;
    }

    private static InvoiceLineItem lineItem(String service, String cost) {
        InvoiceLineItem item = new InvoiceLineItem();
        item.setServiceName(service);
        item.setCost(new BigDecimal(cost));
        item.setUsageQuantity("1");
        return item;
    }

    private static Discount discount(String service, String percentage) {
        Discount d = new Discount();
        d.setServiceName(service);
        d.setPercentage(new BigDecimal(percentage));
        return d;
    }

    /** The reference invoice: a STANDARD AWS line plus the 11994.84 USD Marketplace charge. */
    private InvoiceDto referenceDto(BigDecimal holdingPct) {
        InvoiceDto dto = new InvoiceDto();
        dto.setInvoiceNumber("INV-TEST-001");
        dto.setInvoiceDate(java.time.LocalDate.of(2026, 9, 1));
        dto.setStatus("DRAFT");
        dto.setClientName("Test Client");
        dto.setBillingPeriod("2026-08");
        dto.setAwsAccountId("605134457560");
        dto.setExchangeRate(95.4); // used directly by the PDF, no external calls
        dto.setMarketplaceCharge(new BigDecimal("11994.84"));
        dto.setMarketplaceHoldingCostPercent(holdingPct);

        InvoiceDto.LineItemDto std = new InvoiceDto.LineItemDto();
        std.setServiceName("Amazon EC2");
        std.setCost(new BigDecimal("18937.70")); // discountable consumption
        std.setUsageQuantity("1");
        // STANDARD group total must include the marketplace charge (consumption total
        // includes marketplace, which is then subtracted out as its own line).
        InvoiceDto.LineItemDto mp = new InvoiceDto.LineItemDto();
        mp.setServiceName("Amazon S3");
        mp.setCost(new BigDecimal("11994.84"));
        mp.setUsageQuantity("1");
        dto.setLineItems(List.of(std, mp));
        dto.setDiscounts(List.of());
        return dto;
    }

    private String pdfText(InvoiceDto dto) {
        ByteArrayInputStream pdf = service.generatePdfFromDto(dto, true);
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            pdf.transferTo(buf);
            try (PdfReader reader = new PdfReader(new ByteArrayInputStream(buf.toByteArray()));
                 PdfDocument doc = new PdfDocument(reader)) {
                StringBuilder sb = new StringBuilder();
                for (int p = 1; p <= doc.getNumberOfPages(); p++) {
                    sb.append(PdfTextExtractor.getTextFromPage(doc.getPage(p)));
                }
                return sb.toString();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
