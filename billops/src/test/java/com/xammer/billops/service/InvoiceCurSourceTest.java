package com.xammer.billops.service;

import com.xammer.billops.domain.Client;
import com.xammer.billops.domain.CloudAccount;
import com.xammer.billops.dto.RegionCostDto;
import com.xammer.billops.dto.ResourceCostDto;
import com.xammer.billops.dto.ServiceCostDetailDto;
import com.xammer.billops.dto.cur.CurAggregates;
import com.xammer.billops.repository.AppUserRepository;
import com.xammer.billops.repository.CloudAccountRepository;
import com.xammer.billops.repository.CloudFrontPrivateRateRepository;
import com.xammer.billops.repository.ClientRepository;
import com.xammer.billops.repository.DiscountRepository;
import com.xammer.billops.repository.InvoicePrivatePricingRepository;
import com.xammer.billops.repository.InvoiceRepository;
import com.xammer.billops.service.cdn.CloudFrontPricingService;
import com.xammer.billops.service.cur.CurInvoiceDataService;
import com.xammer.billops.service.cur.CurUnavailable;
import com.xammer.billops.service.cur.CurUnavailableException;
import com.xammer.cloud.domain.Invoice;
import com.xammer.cloud.domain.InvoiceLineItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The promise the invoices page makes: an AWS draft reads the Cost and Usage Report,
 * lists the same rate lines the cost page's "Charges by service" does, and reaches Cost
 * Explorer only when an admin explicitly asks for it.
 *
 * The no-Cost-Explorer checks name every Cost-Explorer-capable method on the draft path,
 * because that is how the promise breaks — not by the CUR branch calling Cost Explorer
 * on purpose, but by someone adding a helper to the shared tail of the method that
 * quietly does.
 */
class InvoiceCurSourceTest {

    private static final String ACCOUNT_ID = "605134457560";
    private static final String EC2 = "Amazon Elastic Compute Cloud";
    private static final String CLOUDFRONT = "Amazon CloudFront";

    private InvoiceRepository invoiceRepository;
    private CloudAccountRepository cloudAccountRepository;
    private BillingService billingService;
    private CloudFrontUsageService cloudFrontUsageService;
    private CurInvoiceDataService curInvoiceDataService;
    private InvoiceManagementService service;
    private CloudAccount account;

    @BeforeEach
    void setUp() {
        invoiceRepository = mock(InvoiceRepository.class);
        cloudAccountRepository = mock(CloudAccountRepository.class);
        billingService = mock(BillingService.class);
        cloudFrontUsageService = mock(CloudFrontUsageService.class);
        curInvoiceDataService = mock(CurInvoiceDataService.class);

        service = new InvoiceManagementService(
                invoiceRepository,
                cloudAccountRepository,
                mock(DiscountRepository.class),
                billingService,
                mock(GcpCostService.class),
                mock(AzureCostService.class),
                mock(AppUserRepository.class),
                mock(EmailService.class),
                cloudFrontUsageService,
                mock(CloudFrontPrivateRateRepository.class),
                mock(InvoicePrivatePricingRepository.class),
                mock(CloudFrontPricingService.class),
                mock(ClientRepository.class),
                mock(RedisCacheService.class),
                mock(AccountAccessHelper.class),
                curInvoiceDataService);

        account = new CloudAccount();
        account.setAwsAccountId(ACCOUNT_ID);
        account.setProvider("AWS");
        account.setAccountName("xammer-local");

        when(cloudAccountRepository.findByAwsAccountIdOrGcpProjectIdOrAzureSubscriptionId(
                ACCOUNT_ID, ACCOUNT_ID, ACCOUNT_ID)).thenReturn(java.util.Optional.of(account));
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // ------------------------------------------------------------------------
    // Built from the report
    // ------------------------------------------------------------------------

    @Test
    void listsTheReportsRateLinesOneInvoiceLineEach() {
        YearMonth month = closedMonth();
        reportHolds(month, 1.5,
                charge(EC2, "ap-south-1", "APS3-BoxUsage:m7i.xlarge",
                        "$0.2121 per On Demand Linux m7i.xlarge Instance Hour", "Hrs", 449.67, 95.38),
                charge("Kiro", "us-east-1", "USE1-KiroEnterprise-Power",
                        "Kiro Enterprise Power", "Count", 8.786, 50.0));

        Invoice draft = generate(month, Invoice.DataSource.CUR);

        assertThat(draft.getDataSource()).isEqualTo(Invoice.DataSource.CUR);
        assertThat(draft.getLineItems()).hasSize(2);
        InvoiceLineItem m7i = draft.getLineItems().get(0);
        assertThat(m7i.getServiceName()).isEqualTo(EC2);
        assertThat(m7i.getRegionName()).isEqualTo("ap-south-1");
        assertThat(m7i.getUsageType()).isEqualTo("APS3-BoxUsage:m7i.xlarge");
        assertThat(m7i.getResourceName()).isEqualTo("$0.2121 per On Demand Linux m7i.xlarge Instance Hour");
        assertThat(m7i.getUsageQuantity()).isEqualTo("449.67");
        assertThat(m7i.getUnit()).isEqualTo("Hrs");
        assertThat(m7i.getCost()).isEqualByComparingTo("95.38");
        assertThat(draft.getMarketplaceCharge()).isEqualByComparingTo("1.5");
        assertNoCostExplorerCalls();
    }

    @Test
    void keepsSavingsPlanNegationsSoTheTotalMatchesTheBill() {
        // The generic line builder drops every line at or below zero. On this path that
        // would bill the covered hours at the full on-demand rate.
        YearMonth month = closedMonth();
        reportHolds(month, 0.0,
                charge(EC2, "ap-south-1", "APS3-BoxUsage:m7i.xlarge",
                        "$0.2121 per On Demand Linux m7i.xlarge Instance Hour", "Hrs", 449.67, 95.38),
                charge(EC2, "ap-south-1", "APS3-BoxUsage:m7i.xlarge",
                        "SavingsPlanNegation used by AccountId : 605134457560 and UsageSku : 9F8VBK2QNNNBSF2Y",
                        "Hrs", 0.525833, -0.11));

        Invoice draft = generate(month, Invoice.DataSource.CUR);

        assertThat(draft.getLineItems()).hasSize(2);
        assertThat(draft.getPreDiscountTotal()).isEqualByComparingTo("95.27");
    }

    @Test
    void keepsSmallQuantitiesReadable() {
        // Three fixed decimals would print 0.000012 GB as zero.
        YearMonth month = closedMonth();
        reportHolds(month, 0.0,
                charge("Amazon Simple Storage Service", "ap-south-1", "APS3-TimedStorage-ByteHrs",
                        "$0.025 per GB-Mo", "GB-Mo", 0.000012, 0.0101),
                charge("Amazon Simple Storage Service", "ap-south-1", "APS3-Requests-Tier1",
                        "$0.005 per 1,000 requests", "Requests", 147128739, 735.64));

        Invoice draft = generate(month, Invoice.DataSource.CUR);

        assertThat(draft.getLineItems()).extracting(InvoiceLineItem::getUsageQuantity)
                .containsExactly("0.000012", "147128739");
    }

    @Test
    void closedMonthIsNotProvisionalOpenMonthIs() {
        YearMonth closed = YearMonth.now().minusMonths(1);
        reportHolds(closed, 0.0, charge(EC2, "ap-south-1", "APS3-BoxUsage", "hours", "Hrs", 1, 10.0));
        YearMonth open = YearMonth.now();
        reportHolds(open, 0.0, charge(EC2, "ap-south-1", "APS3-BoxUsage", "hours", "Hrs", 1, 10.0));

        assertThat(generate(closed, Invoice.DataSource.CUR).isProvisional()).isFalse();
        assertThat(generate(open, Invoice.DataSource.CUR).isProvisional()).isTrue();
    }

    // ------------------------------------------------------------------------
    // Refusals
    // ------------------------------------------------------------------------

    @Test
    void aRefusalSavesNothingAndCallsNoCostExplorer() {
        YearMonth month = YearMonth.now().minusMonths(4);
        when(curInvoiceDataService.ensureMonthLoaded(account, month, false))
                .thenThrow(new CurUnavailableException(CurUnavailable.PERIOD_BEFORE_REPORT_START,
                        "the report starts at 2026-09"));

        assertThatThrownBy(() -> generate(month, Invoice.DataSource.CUR))
                .isInstanceOf(CurUnavailableException.class)
                .hasMessageContaining("2026-09");

        verify(invoiceRepository, never()).save(any());
        assertNoCostExplorerCalls();
    }

    @Test
    void aMonthWithNoRateLinesIsRefused() {
        YearMonth month = closedMonth();
        reportHolds(month, 0.0);

        assertThatThrownBy(() -> generate(month, Invoice.DataSource.CUR))
                .isInstanceOf(CurUnavailableException.class)
                .extracting(e -> ((CurUnavailableException) e).getReason())
                .isEqualTo(CurUnavailable.NO_DATA_FOR_MONTH);
        verify(invoiceRepository, never()).save(any());
    }

    @Test
    void refusesRatherThanBillingZeroForUnmeasuredMarketplace() {
        YearMonth month = closedMonth();
        reportHolds(month, null, charge(EC2, "ap-south-1", "APS3-BoxUsage", "hours", "Hrs", 1, 10.0));

        assertThatThrownBy(() -> generate(month, Invoice.DataSource.CUR))
                .isInstanceOf(CurUnavailableException.class)
                .extracting(e -> ((CurUnavailableException) e).getReason())
                .isEqualTo(CurUnavailable.MARKETPLACE_UNMEASURED);
        assertNoCostExplorerCalls();
    }

    @Test
    void refusesCloudFrontChargesItCannotRepriceAtContractRates() {
        // With no usage to reprice from, the CDN would go out at AWS's own cost rather
        // than the rate the customer signed for.
        withClient();
        YearMonth month = closedMonth();
        reportHolds(month, 0.0, charge(CLOUDFRONT, "Global", "US-DataTransfer-Out-Bytes",
                "$0.085 per GB", "GB", 400, 40.0));
        when(cloudFrontUsageService.getUsageFromCurForInvoice(ACCOUNT_ID, month)).thenReturn(List.of());

        assertThatThrownBy(() -> generate(month, Invoice.DataSource.CUR))
                .isInstanceOf(CurUnavailableException.class)
                .extracting(e -> ((CurUnavailableException) e).getReason())
                .isEqualTo(CurUnavailable.CLOUDFRONT_USAGE_MISSING);
        verify(cloudFrontUsageService, never()).getUsageFromAws(anyString(), any());
    }

    @Test
    void anAccountWithNoCloudFrontIsNotRefusedForHavingNoCloudFrontUsage() {
        withClient();
        YearMonth month = closedMonth();
        reportHolds(month, 0.0, charge(EC2, "ap-south-1", "APS3-BoxUsage", "hours", "Hrs", 1, 10.0));
        when(cloudFrontUsageService.getUsageFromCurForInvoice(ACCOUNT_ID, month)).thenReturn(List.of());

        Invoice draft = generate(month, Invoice.DataSource.CUR);

        assertThat(draft.getLineItems()).hasSize(1);
        assertNoCostExplorerCalls();
    }

    // ------------------------------------------------------------------------
    // Cost Explorer, and other providers
    // ------------------------------------------------------------------------

    @Test
    void costExplorerIsUsedOnlyWhenAskedForAndNeverTouchesTheReport() {
        YearMonth month = closedMonth();
        when(billingService.getMonthlySnapshotReport(ACCOUNT_ID, month.getYear(), month.getMonthValue()))
                .thenReturn(List.of(ceService(EC2 + " - Compute", "ap-south-1", 90.0)));

        Invoice draft = generate(month, Invoice.DataSource.COST_EXPLORER);

        assertThat(draft.getDataSource()).isEqualTo(Invoice.DataSource.COST_EXPLORER);
        assertThat(draft.getLineItems()).singleElement()
                .satisfies(li -> assertThat(li.getUsageType()).isNull());
        verify(billingService).getMarketplaceCharge(ACCOUNT_ID, month.getYear(), month.getMonthValue(), false);
        verifyNoInteractions(curInvoiceDataService);
    }

    @Test
    void gcpAccountsIgnoreTheSource() {
        account.setProvider("GCP");

        Invoice draft = service.generateDraftInvoice(ACCOUNT_ID, 2026, 8, false, Invoice.DataSource.CUR);

        assertThat(draft.getDataSource()).isNull();
        verifyNoInteractions(curInvoiceDataService);
    }

    // ------------------------------------------------------------------------
    // Presentation
    // ------------------------------------------------------------------------

    @Test
    void namesRegionsAsTheBillDoes() {
        assertThat(InvoiceManagementService.regionDisplayName("ap-south-1")).isEqualTo("Asia Pacific (Mumbai)");
        assertThat(InvoiceManagementService.regionDisplayName("us-east-1")).isEqualTo("US East (N. Virginia)");
        // Not a region code: shown as it is rather than guessed at.
        assertThat(InvoiceManagementService.regionDisplayName("Global")).isEqualTo("Global");
        assertThat(InvoiceManagementService.regionDisplayName(null)).isEmpty();
    }

    // ------------------------------------------------------------------------

    /**
     * Every method on the draft path that can reach Cost Explorer. getMonthlySnapshotReport
     * is listed because it falls through to Cost-Explorer-derived snapshots.
     */
    private void assertNoCostExplorerCalls() {
        verify(billingService, never()).getBillingData(anyList(), anyInt(), anyInt(), anyBoolean());
        verify(billingService, never()).getDetailedBillingReport(anyList(), anyInt(), anyInt(), anyBoolean());
        verify(billingService, never()).getMonthlySnapshotReport(anyString(), anyInt(), anyInt());
        verify(billingService, never()).getMarketplaceCharge(anyString(), anyInt(), anyInt(), anyBoolean());
        verify(cloudFrontUsageService, never()).getUsageFromAws(anyString(), any());
    }

    private Invoice generate(YearMonth month, Invoice.DataSource source) {
        return service.generateDraftInvoice(ACCOUNT_ID, month.getYear(), month.getMonthValue(), false, source);
    }

    private static YearMonth closedMonth() {
        return YearMonth.now().minusMonths(2);
    }

    private void withClient() {
        Client client = new Client();
        client.setId(7L);
        account.setClient(client);
    }

    private void reportHolds(YearMonth month, Double marketplace, CurAggregates.ChargeEntry... charges) {
        CurAggregates aggregates = new CurAggregates();
        aggregates.setCharges(List.of(charges));
        aggregates.setMarketplace(marketplace);
        when(curInvoiceDataService.ensureMonthLoaded(account, month, false)).thenReturn(aggregates);
    }

    private static CurAggregates.ChargeEntry charge(String service, String region, String usageType,
                                                    String description, String unit, double quantity, double cost) {
        return new CurAggregates.ChargeEntry(service, region, usageType, description, unit, quantity, cost);
    }

    private static ServiceCostDetailDto ceService(String name, String region, double cost) {
        ResourceCostDto resource = new ResourceCostDto(name + "-usage", "usage", 1.0, cost, name + "-usage");
        resource.setQuantity(1.0);
        resource.setUnit("Hrs");

        RegionCostDto regionCost = new RegionCostDto();
        regionCost.setRegionName(region);
        regionCost.setCost(cost);
        regionCost.setResources(List.of(resource));

        ServiceCostDetailDto dto = new ServiceCostDetailDto();
        dto.setServiceName(name);
        dto.setCost(cost);
        dto.setRegionCosts(List.of(regionCost));
        return dto;
    }
}
