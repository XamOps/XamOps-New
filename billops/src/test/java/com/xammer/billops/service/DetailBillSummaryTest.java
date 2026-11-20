package com.xammer.billops.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The summary the detail bill closes with: charges, what is billed at private rates, what
 * the overall discount was taken from, the discount and the taxable value.
 *
 * <p>The admin invoice page already shows these. The bill used to stop at the TOTAL row,
 * the gross charges, so a customer had no way to see from the bill how the gross became
 * the amount they were taxed on.
 *
 * <p>Invoices here are priced and discounted through the service's own methods, so the
 * saved totals come from {@code recalculateTotals}. They are then sent through the JSON
 * round trip the page makes before it posts the invoice back for the bill, and the bill's
 * text is read out of the PDF.
 */
class DetailBillSummaryTest {

    private static final long INVOICE_ID = 1L;
    private static final String EC2 = "Amazon Elastic Compute Cloud";
    private static final String CLOUDFRONT = "Amazon CloudFront";
    private static final String S3 = "Amazon Simple Storage Service";

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private InvoiceManagementService service;
    private Invoice invoice;

    @BeforeEach
    void setUp() {
        InvoiceRepository invoiceRepository = mock(InvoiceRepository.class);
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

        invoice = new Invoice();
        invoice.setId(INVOICE_ID);
        invoice.setStatus(Invoice.InvoiceStatus.DRAFT);
        invoice.setBillingPeriod("2026-06");
        invoice.setLineItems(new ArrayList<>());
        invoice.setDiscounts(new ArrayList<>());
        invoice.setPrivatePricings(new ArrayList<>());

        when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.of(invoice));
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // ── The invoice in the screenshot ───────────────────────────────────────

    /**
     * The admin page's figures for the invoice that prompted this: $9,685.51 of charges,
     * $1,962.60 of it at two shielded private rates, and a 10% overall discount taken from
     * the other $7,722.91.
     */
    @Test
    @DisplayName("the screenshot's invoice prints the page's figures")
    void theScreenshotsInvoicePrintsThePagesFigures() {
        InvoiceDto dto = new InvoiceDto();
        dto.setPreDiscountTotal(new BigDecimal("9685.5071"));
        dto.setPrivatePricedTotal(new BigDecimal("1962.6000"));
        dto.setDiscountShieldedTotal(new BigDecimal("1962.6000"));
        // 10% of (9,685.5071 - 1,962.6000), at the scale recalculateTotals stores.
        dto.setDiscountAmount(new BigDecimal("772.2907"));
        dto.setPrivatePricing(List.of(new InvoiceDto.PrivatePricingDto(), new InvoiceDto.PrivatePricingDto()));
        dto.setDiscounts(List.of(new InvoiceDto.DiscountDto()));

        InvoiceManagementService.DetailBillSummary s =
                InvoiceManagementService.detailBillSummary(dto, new BigDecimal("9685.5071"), 26, 342);

        assertThat(InvoiceManagementService.summaryUsd(s.charges())).isEqualTo("$9,685.51");
        assertThat(InvoiceManagementService.summaryUsd(s.privatePriced())).isEqualTo("$1,962.60");
        assertThat(s.rateCount()).isEqualTo(2);
        assertThat(InvoiceManagementService.summaryUsd(s.discountable())).isEqualTo("$7,722.91");
        assertThat(InvoiceManagementService.discountableNote(s)).isEqualTo("Total consumption less $1,962.60 private rates consumption");
        assertThat(InvoiceManagementService.summaryUsd(s.discount().negate())).isEqualTo("-$772.29");
        assertThat(InvoiceManagementService.summaryUsd(s.taxable())).isEqualTo("$8,913.22");
    }

    // ── The bill itself ─────────────────────────────────────────────────────

    @Test
    @DisplayName("the bill closes with the summary, under the TOTAL row, in the page's order")
    void theBillClosesWithTheSummary() throws Exception {
        // $7,125 of charges: $7,000 of EC2 at provider cost, and CloudFront and S3 at two
        // shielded private rates ($85 + $40).
        invoice.getLineItems().addAll(List.of(
                line(EC2, "us-east-1", "BoxUsage:m7i.xlarge", "10000", "Hrs", "7000.0000"),
                line(CLOUDFRONT, "", "DataTransfer-Out-Bytes", "1000", "GB", "50.0000"),
                line(S3, "us-east-1", "TimedStorage-ByteHrs", "2000", "GB-Mo", "46.0000")));
        service.applyPrivatePricing(INVOICE_ID, rate(CLOUDFRONT, "0.085", "GB", true));
        service.applyPrivatePricing(INVOICE_ID, rate(S3, "0.02", "GB-Mo", true));
        service.applyDiscountToInvoice(INVOICE_ID, "ALL", new BigDecimal("10"));

        String text = billText();

        // The TOTAL row is unchanged; the summary follows it.
        assertThat(text).contains("TOTAL").contains("$7125.0000");
        String summary = summaryOf(text);

        assertThat(summary).contains("Total consumption").contains("3 services, 3 lines").contains("$7,125.00");
        assertThat(summary).contains("Private rates consumption")
                .contains("2 rates applied \u00B7 $125.00 shielded from the overall discount")
                .contains("$125.00");
        assertThat(summary).contains("Discountable value")
                .contains("Total consumption less $125.00 private rates consumption")
                .contains("$7,000.00");
        assertThat(summary).contains("Discounts").contains("1 applied").contains("-$700.00");
        // No intermediate rows; the Taxable value note carries the calculation instead.
        assertThat(summary).doesNotContain("Value after discounts").doesNotContain("Add back");
        assertThat(summary).contains("Taxable value")
                .contains("Discountable value less Discounts, plus Private rates consumption")
                .contains("$6,425.00");

        assertThat(summary.indexOf("Total consumption")).isLessThan(summary.indexOf("Private rates consumption"));
        assertThat(summary.indexOf("Private rates consumption")).isLessThan(summary.indexOf("Discountable value"));
        assertThat(summary.indexOf("Discountable value")).isLessThan(summary.indexOf("Discounts"));
        assertThat(summary.indexOf("Discounts")).isLessThan(summary.indexOf("Taxable value"));
    }

    @Test
    @DisplayName("the printed taxable value is the one the invoice was saved with")
    void thePrintedTaxableValueIsTheSavedOne() throws Exception {
        invoice.getLineItems().addAll(List.of(
                line(EC2, "us-east-1", "BoxUsage:m7i.xlarge", "10000", "Hrs", "7000.0000"),
                line(CLOUDFRONT, "", "DataTransfer-Out-Bytes", "1000", "GB", "50.0000")));
        service.applyPrivatePricing(INVOICE_ID, rate(CLOUDFRONT, "0.085", "GB", true));
        service.applyDiscountToInvoice(INVOICE_ID, "ALL", new BigDecimal("12.5"));

        BigDecimal savedTaxable = invoice.getAmount().subtract(invoice.getTaxAmount());
        // 7,085 - 12.5% of 7,000
        assertThat(savedTaxable).isEqualByComparingTo("6210.0000");

        assertThat(summaryOf(billText()))
                .contains("Taxable value")
                .contains(InvoiceManagementService.summaryUsd(savedTaxable))
                .contains("$6,210.00");
    }

    @Test
    @DisplayName("without private rates, the private-rate lines are left out")
    void withoutPrivateRatesThosePrivateRateLinesAreLeftOut() throws Exception {
        invoice.getLineItems().add(line(EC2, "us-east-1", "BoxUsage:m7i.xlarge", "1000", "Hrs", "1000.0000"));
        service.applyDiscountToInvoice(INVOICE_ID, "ALL", new BigDecimal("10"));

        String summary = summaryOf(billText());

        assertThat(summary).contains("1 service, 1 line").contains("$1,000.00");
        assertThat(summary).doesNotContain("Private rates consumption").doesNotContain("Discountable value");
        assertThat(summary).contains("Discounts").contains("-$100.00");
        assertThat(summary).contains("Taxable value").contains("$900.00");
        assertThat(summary).contains("Total consumption less Discounts");
    }

    @Test
    @DisplayName("an unshielded rate leaves the overall discount on every charge, and says so")
    void anUnshieldedRateLeavesTheDiscountOnEveryCharge() throws Exception {
        invoice.getLineItems().addAll(List.of(
                line(EC2, "us-east-1", "BoxUsage:m7i.xlarge", "1000", "Hrs", "100.0000"),
                line(CLOUDFRONT, "", "DataTransfer-Out-Bytes", "1000", "GB", "50.0000")));
        service.applyPrivatePricing(INVOICE_ID, rate(CLOUDFRONT, "0.085", "GB", false));
        service.applyDiscountToInvoice(INVOICE_ID, "ALL", new BigDecimal("10"));

        String summary = summaryOf(billText());

        assertThat(summary).contains("Private rates consumption").contains("1 rate applied").contains("$85.00");
        assertThat(summary).doesNotContain("shielded");
        // Nothing held out: the discount base is every charge, $185.
        assertThat(summary).contains("Discountable value")
                .contains("The overall discount applies to all consumption")
                .contains("$185.00");
        assertThat(summary).contains("-$18.50").contains("$166.50");
    }

    @Test
    @DisplayName("Marketplace is held out of the discountable value, and its holding cost is added back")
    void marketplaceAndItsHoldingCostAddUp() throws Exception {
        invoice.getLineItems().addAll(List.of(
                line(EC2, "us-east-1", "BoxUsage:m7i.xlarge", "1000", "Hrs", "1000.0000"),
                line("AWS Marketplace", "Global", "Subscription", "1", "N/A", "100.0000")));
        invoice.setMarketplaceCharge(new BigDecimal("100.0000"));
        service.applyDiscountToInvoice(INVOICE_ID, "ALL", new BigDecimal("10"));
        service.setMarketplaceHoldingCost(INVOICE_ID, new BigDecimal("15"));

        String summary = summaryOf(billText());

        assertThat(summary).contains("$1,100.00");
        assertThat(summary).doesNotContain("Private rates consumption");
        assertThat(summary).contains("Discountable value")
                .contains("Total consumption less $100.00 AWS Marketplace")
                .contains("$1,000.00");
        assertThat(summary).contains("-$100.00");
        assertThat(summary).contains("Add: Holding cost (AWS Marketplace)")
                .contains("15% of $100.00 AWS Marketplace")
                .contains("+$15.00");
        // 1,100 - 100 + 15, which is what the invoice was saved with.
        assertThat(summary).contains("Taxable value").contains("$1,015.00");
        assertThat(invoice.getAmount().subtract(invoice.getTaxAmount())).isEqualByComparingTo("1015.00");
    }

    // ── Amounts ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("amounts print as the page prints them")
    void amountsPrintAsThePagePrintsThem() {
        assertThat(InvoiceManagementService.summaryUsd(new BigDecimal("9685.5071"))).isEqualTo("$9,685.51");
        assertThat(InvoiceManagementService.summaryUsd(new BigDecimal("-772.2907"))).isEqualTo("-$772.29");
        assertThat(InvoiceManagementService.summaryUsd(new BigDecimal("0.125"))).isEqualTo("$0.13");
        // Under a cent keeps its digits rather than reading as nothing.
        assertThat(InvoiceManagementService.summaryUsd(new BigDecimal("0.0042"))).isEqualTo("$0.0042");
        assertThat(InvoiceManagementService.summaryUsd(BigDecimal.ZERO)).isEqualTo("$0.00");
        assertThat(InvoiceManagementService.summaryUsd(null)).isEqualTo("$0.00");
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static InvoiceLineItem line(String service, String region, String usageType,
                                        String quantity, String unit, String cost) {
        InvoiceLineItem item = new InvoiceLineItem();
        item.setServiceName(service);
        item.setRegionName(region);
        item.setUsageType(usageType);
        item.setResourceName(usageType);
        item.setUsageQuantity(quantity);
        item.setUnit(unit);
        item.setCost(new BigDecimal(cost));
        return item;
    }

    private static PrivatePricingRequestDto rate(String service, String unitRate, String rateUnit, boolean shield) {
        PrivatePricingRequestDto dto = new PrivatePricingRequestDto();
        dto.setServiceName(service);
        dto.setUnitRate(new BigDecimal(unitRate));
        dto.setRateUnit(rateUnit);
        dto.setExcludeFromOverallDiscount(shield);
        return dto;
    }

    /** The invoice as the page posts it back -- serialized, then read into a fresh DTO -- rendered and read out. */
    private String billText() throws Exception {
        InvoiceDto posted = mapper.readValue(
                mapper.writeValueAsString(InvoiceDto.fromEntity(invoice)), InvoiceDto.class);

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        service.generateDetailBillPdf(posted).transferTo(buf);
        try (PdfReader reader = new PdfReader(new ByteArrayInputStream(buf.toByteArray()));
             PdfDocument doc = new PdfDocument(reader)) {
            StringBuilder sb = new StringBuilder();
            for (int p = 1; p <= doc.getNumberOfPages(); p++) {
                sb.append(PdfTextExtractor.getTextFromPage(doc.getPage(p))).append('\n');
            }
            return sb.toString();
        }
    }

    /** The bill from its summary's header on, so line descriptions cannot satisfy an assertion. */
    private static String summaryOf(String text) {
        int start = text.indexOf("SUMMARY");
        assertThat(start).as("the bill has a SUMMARY section").isNotNegative();
        return text.substring(start);
    }
}

