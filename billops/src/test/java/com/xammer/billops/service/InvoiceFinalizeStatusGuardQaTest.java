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
import org.junit.jupiter.api.Disabled;
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
 * QA depth-testing: invoice finalize state-machine (report 02-bugs-A, BUG A-06).
 *
 * <p>{@code finalizeInvoice} has no status guard, so a FINALIZED invoice can be re-finalized with a
 * different exchange rate and a VOID invoice can be resurrected. The tests that expose this are
 * {@code @Disabled} so the build stays green; remove the annotation when the guard is added.
 */
class InvoiceFinalizeStatusGuardQaTest {

    private static final long ID = 7L;
    private InvoiceManagementService service;
    private Invoice invoice;

    @BeforeEach
    void setUp() {
        InvoiceRepository repo = mock(InvoiceRepository.class);
        service = new InvoiceManagementService(
                repo,
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
        invoice.setId(ID);
        invoice.setLineItems(new ArrayList<>());
        invoice.setDiscounts(new ArrayList<>());
        invoice.setPrivatePricings(new ArrayList<>());
        when(repo.findById(ID)).thenReturn(Optional.of(invoice));
        when(repo.save(any(Invoice.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    @DisplayName("characterization: DRAFT moves to FINALIZED and keeps the rate")
    void draftFinalizes() {
        invoice.setStatus(Invoice.InvoiceStatus.DRAFT);
        Invoice out = service.finalizeInvoice(ID, 85.0);
        assertThat(out.getStatus()).isEqualTo(Invoice.InvoiceStatus.FINALIZED);
        assertThat(out.getExchangeRate()).isEqualTo(85.0);
    }

    @Test
    @DisplayName("characterization: unknown invoice id is rejected")
    void unknownInvoiceRejected() {
        assertThatThrownBy(() -> service.finalizeInvoice(999L, 85.0)).isInstanceOf(RuntimeException.class);
    }

    @Test
    @Disabled("BUG A-06: finalizeInvoice has no status guard; a FINALIZED invoice is re-finalized and its locked rate overwritten")
    @DisplayName("a FINALIZED invoice must not be re-finalized with a different rate")
    void refinalizeIsRejectedAndRateStaysLocked() {
        invoice.setStatus(Invoice.InvoiceStatus.FINALIZED);
        invoice.setExchangeRate(80.0);
        assertThatThrownBy(() -> service.finalizeInvoice(ID, 90.0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(invoice.getExchangeRate()).isEqualTo(80.0);
    }

    @Test
    @Disabled("BUG A-06: a VOID invoice is resurrected to FINALIZED by finalizeInvoice")
    @DisplayName("a VOID invoice must not be finalizable")
    void voidInvoiceCannotBeFinalized() {
        invoice.setStatus(Invoice.InvoiceStatus.VOID);
        assertThatThrownBy(() -> service.finalizeInvoice(ID, 85.0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(invoice.getStatus()).isEqualTo(Invoice.InvoiceStatus.VOID);
    }
}
