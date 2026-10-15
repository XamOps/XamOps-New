package com.xammer.billops.service;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The USD→INR rate an admin locks in when finalizing an invoice.
 *
 * <p>The rate is persisted on the invoice at finalize time so a finalized bill renders at
 * the figure it was finalized with, rather than at whatever the live rate happens to be
 * when the PDF is next drawn. A rate at or below 1.0 is a mistake — INR per USD is well
 * above that — so it is refused rather than saved.
 */
class InvoiceFinalizeExchangeRateTest {

    private static final long INVOICE_ID = 1L;

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

        invoice = new Invoice();
        invoice.setId(INVOICE_ID);
        invoice.setStatus(Invoice.InvoiceStatus.DRAFT);
        invoice.setLineItems(new ArrayList<>());
        invoice.setDiscounts(new ArrayList<>());
        invoice.setPrivatePricings(new ArrayList<>());

        when(invoiceRepository.findById(INVOICE_ID)).thenReturn(Optional.of(invoice));
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("a valid rate is set on the invoice before it is finalized")
    void aValidRateIsPersistedAndTheInvoiceIsFinalized() {
        Invoice finalized = service.finalizeInvoice(INVOICE_ID, 92.5);

        assertThat(finalized.getExchangeRate()).isEqualTo(92.5);
        assertThat(finalized.getStatus()).isEqualTo(Invoice.InvoiceStatus.FINALIZED);
    }

    @Test
    @DisplayName("a null rate leaves the field untouched and still finalizes")
    void aNullRateLeavesTheFieldNull() {
        Invoice finalized = service.finalizeInvoice(INVOICE_ID, null);

        assertThat(finalized.getExchangeRate()).isNull();
        assertThat(finalized.getStatus()).isEqualTo(Invoice.InvoiceStatus.FINALIZED);
    }

    @Test
    @DisplayName("the no-rate overload finalizes without touching the rate")
    void theLegacyOverloadFinalizesWithoutARate() {
        Invoice finalized = service.finalizeInvoice(INVOICE_ID);

        assertThat(finalized.getExchangeRate()).isNull();
        assertThat(finalized.getStatus()).isEqualTo(Invoice.InvoiceStatus.FINALIZED);
    }

    @Test
    @DisplayName("a rate at or below 1.0 is refused and nothing is finalized")
    void aRateAtOrBelowOneIsRefused() {
        assertThatThrownBy(() -> service.finalizeInvoice(INVOICE_ID, 1.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("greater than 1");

        assertThat(invoice.getExchangeRate()).isNull();
        assertThat(invoice.getStatus()).isEqualTo(Invoice.InvoiceStatus.DRAFT);
    }
}
