package com.xammer.cloud.service.billops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.EbsVolumeAction;
import com.xammer.cloud.dto.ClientSavingsDashboardDto;
import com.xammer.cloud.repository.CachedDataRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.DailySpotSavingsRepository;
import com.xammer.cloud.service.autospotting.ledger.money.Money;
import com.xammer.cloud.service.autospotting.ledger.read.SavingsLedgerReadService;
import com.xammer.cloud.service.autospotting.ledger.read.SavingsLedgerReadService.*;
import com.xammer.cloud.repository.EbsVolumeActionRepository;
import com.xammer.cloud.service.RedisCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClientSavingsServiceTest {

    @Mock RedisCacheService redisCacheService;
    @Mock CloudAccountRepository cloudAccountRepository;
    @Mock DailySpotSavingsRepository dailySpotSavingsRepository;
    @Mock SavingsLedgerReadService ledgerReadService;
    @Mock EbsVolumeActionRepository ebsVolumeActionRepository;
    @Mock CachedDataRepository cachedDataRepository;

    @InjectMocks ClientSavingsService service;

    @BeforeEach
    void setUp() {
        service.objectMapper = new ObjectMapper();
        org.mockito.Mockito.lenient().when(redisCacheService.get(anyString(), any(com.fasterxml.jackson.core.type.TypeReference.class)))
            .thenReturn(Optional.empty());
        org.mockito.Mockito.lenient().when(ledgerReadService.latestClosedAt(any())).thenReturn(Optional.empty());
        org.mockito.Mockito.lenient().when(cachedDataRepository.findById(anyString())).thenReturn(Optional.empty());
        org.mockito.Mockito.lenient().when(ebsVolumeActionRepository.findByAccountIdAndStatusAndInitiatedAtBetween(
            anyLong(), anyString(), any(Instant.class), any(Instant.class))).thenReturn(List.of());
    }

    private CloudAccount makeAccount(Long id, String provider, String providerAccountId) {
        CloudAccount a = new CloudAccount();
        a.setId(id);
        a.setProvider(provider);
        if ("AWS".equals(provider)) a.setAwsAccountId(providerAccountId);
        if ("GCP".equals(provider)) a.setGcpProjectId(providerAccountId);
        if ("Azure".equals(provider)) a.setAzureSubscriptionId(providerAccountId);
        Client client = new Client();
        client.setName("Acme Corp");
        a.setClient(client);
        return a;
    }

    private static Money usd(String v) { return Money.of(v, "USD"); }

    private static ClientSpotSavings spot(List<ProviderSavings> p, List<YearMonth> months, List<DailyAccrual> daily) {
        return new ClientSpotSavings(p, List.of(), months, daily);
    }

    private static final LocalDate START = LocalDate.of(2025, 4, 1);
    private static final LocalDate END = LocalDate.of(2025, 4, 7);

    @Test
    void shadowStatement_addsToShadowNotInvoiced_andToNeitherRealizedNorTotals() {
        when(cloudAccountRepository.findByClientId(99L)).thenReturn(List.of(makeAccount(1L, "AWS", "123456789012")));
        when(ledgerReadService.getClientSpotSavings(99L, YearMonth.of(2025, 4), YearMonth.of(2025, 4)))
            .thenReturn(spot(List.of(new ProviderSavings("AWS", usd("0"), usd("123.456"), usd("0"))), List.of(), List.of()));
        ClientSavingsDashboardDto r = service.getSavings(99L, START, END);
        assertThat(r.getShadowNotInvoicedAws()).isEqualByComparingTo("123.46");
        assertThat(r.getRealizedSpotAws()).isEqualByComparingTo("0");
        assertThat(r.getRealizedTotal()).isZero();
        assertThat(r.getGrandTotal()).isZero();
    }

    @Test
    void invoiceableStatement_addsToRealized_andListsItsMonths() {
        when(cloudAccountRepository.findByClientId(99L)).thenReturn(List.of(makeAccount(1L, "AWS", "123456789012")));
        when(ledgerReadService.getClientSpotSavings(any(), any(), any()))
            .thenReturn(spot(List.of(new ProviderSavings("AWS", usd("244.48"), usd("0"), usd("0"))),
                List.of(YearMonth.of(2025, 4)), List.of()));
        ClientSavingsDashboardDto r = service.getSavings(99L, START, END);
        assertThat(r.getRealizedSpotAws()).isEqualByComparingTo("244.48");
        assertThat(r.getRealizedSpotMonths()).containsExactly("2025-04");
        assertThat(r.getRealizedTotal()).isEqualTo(244.48);
        assertThat(r.getShadowNotInvoicedAws()).isEqualByComparingTo("0");
    }

    @Test
    void openMonth_addsOnlyToAccruing() {
        when(cloudAccountRepository.findByClientId(99L)).thenReturn(List.of(makeAccount(1L, "AWS", "123456789012")));
        when(ledgerReadService.getClientSpotSavings(any(), any(), any()))
            .thenReturn(spot(List.of(new ProviderSavings("AWS", usd("0"), usd("0"), usd("9.72"))), List.of(), List.of()));
        ClientSavingsDashboardDto r = service.getSavings(99L, START, END);
        assertThat(r.getAccruingSpotAws()).isEqualByComparingTo("9.72");
        assertThat(r.getRealizedSpotAws()).isEqualByComparingTo("0");
        assertThat(r.getGrandTotal()).isZero();
    }

    @Test
    void timeSeries_usesLedgerDailyAccrual_withItsLabel_andLegacyStaysApart() {
        when(cloudAccountRepository.findByClientId(99L)).thenReturn(List.of(makeAccount(1L, "AWS", "123456789012")));
        when(dailySpotSavingsRepository.sumLegacyAmount(List.of(1L), START, END)).thenReturn(500.0);
        when(ledgerReadService.getClientSpotSavings(any(), any(), any()))
            .thenReturn(spot(List.of(), List.of(), List.of(new DailyAccrual(START.plusDays(2), "AWS", usd("9.725")))));
        ClientSavingsDashboardDto r = service.getSavings(99L, START, END);
        assertThat(r.getTimeSeries()).hasSize(7);
        assertThat(r.getTimeSeries().get(2).getAwsSpot()).isEqualByComparingTo("9.73");
        assertThat(r.getSpotSeriesLabel()).isEqualTo("Daily accrual, before statement rounding");
        assertThat(r.getLegacySpotUnverifiedAws()).isEqualByComparingTo("500.00");
        assertThat(r.getRealizedTotal()).isZero();
        verify(dailySpotSavingsRepository, never()).findByAccountIdAndDateBetweenAndLegacyFalseOrderByDateAsc(any(), any(), any());
    }

    @Test
    void cacheKey_carriesTheLatestClosedAt() {
        when(cloudAccountRepository.findByClientId(99L)).thenReturn(List.of());
        when(ledgerReadService.latestClosedAt(99L)).thenReturn(Optional.of(Instant.parse("2025-05-02T08:00:00Z")));
        when(ledgerReadService.getClientSpotSavings(any(), any(), any())).thenReturn(spot(List.of(), List.of(), List.of()));
        service.getSavings(99L, START, END);
        verify(redisCacheService).get(org.mockito.ArgumentMatchers.contains("2025-05-02T08:00:00Z"), any(com.fasterxml.jackson.core.type.TypeReference.class));
    }

    @Test
    void getSavings_sumsPotentialRightsizingFromCachedData() throws Exception {
        when(cloudAccountRepository.findByClientId(99L)).thenReturn(List.of(makeAccount(2L, "GCP", "my-project")));
        when(ledgerReadService.getClientSpotSavings(any(), any(), any())).thenReturn(spot(List.of(), List.of(), List.of()));
        com.xammer.cloud.domain.CachedData cd = new com.xammer.cloud.domain.CachedData();
        cd.setCacheKey("GCP_RIGHTSIZING::my-project");
        cd.setJsonData("[{\"monthlySavings\": 200.0}, {\"monthlySavings\": 150.0}]");
        cd.setLastUpdated(java.time.LocalDateTime.now());
        when(cachedDataRepository.findById("GCP_RIGHTSIZING::my-project")).thenReturn(Optional.of(cd));
        assertThat(service.getSavings(99L, START, END).getPotentialRightsizingGcp()).isEqualTo(350.0);
    }

    @Test
    void getSavings_timeSeriesHasOneEntryPerDay_andZeroWhenNothing() {
        when(cloudAccountRepository.findByClientId(99L)).thenReturn(List.of());
        when(ledgerReadService.getClientSpotSavings(any(), any(), any())).thenReturn(spot(List.of(), List.of(), List.of()));
        ClientSavingsDashboardDto r = service.getSavings(99L, START, END);
        assertThat(r.getTimeSeries()).hasSize(7);
        assertThat(r.getTimeSeries().get(6).getDate()).isEqualTo("2025-04-07");
        assertThat(r.getGrandTotal()).isZero();
    }

    private static com.xammer.cloud.domain.CachedData snapshot(String key, String json) {
        return new com.xammer.cloud.domain.CachedData(key, json, java.time.LocalDateTime.of(2026, 11, 1, 0, 0));
    }

    /** Serves these snapshots by key; any other key has none. */
    private void snapshots(java.util.Map<String, String> byKey) {
        when(cachedDataRepository.findById(anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            return Optional.ofNullable(byKey.get(key)).map(json -> snapshot(key, json));
        });
    }

    private void noRealizedRows() {
        org.mockito.Mockito.lenient().when(ledgerReadService.getClientSpotSavings(any(), any(), any()))
                .thenReturn(spot(List.of(), List.of(), List.of()));
    }
    @Test
    void otherOptimizations_isTheDashboardTotalBeyondWhatOtherColumnsCount() {
        CloudAccount aws = makeAccount(1L, "AWS", "123456789012");
        CloudAccount gcp = makeAccount(2L, "GCP", "my-project");
        CloudAccount azure = makeAccount(3L, "Azure", "sub-1");
        when(cloudAccountRepository.findByClientId(99L)).thenReturn(List.of(aws, gcp, azure));
        noRealizedRows();
        snapshots(java.util.Map.of(
            // AWS dashboard: 2.50 rightsizing inside a 93.10 total (waste + AutoSpotting/EBS/CloudSitter).
            "DASHBOARD_OPT::123456789012",
                "{\"selectedAccount\":{\"optimizationSummary\":{\"totalPotentialSavings\":93.10},"
                + "\"ebsRecommendations\":[{\"estimatedMonthlySavings\":2.5}]}}",
            // GCP: 5 rightsizing; the dashboard's 58.24 already includes it.
            "GCP_RIGHTSIZING::my-project", "[{\"monthlySavings\":5}]",
            "DASHBOARD_OPT::my-project", "{\"selectedAccount\":{\"optimizationSummary\":{\"totalPotentialSavings\":58.24}}}",
            // Azure: total is the disk estimate only; idle resources listed beside it. "idle-vm" is
            // both idle and a downsize candidate, so its rightsizing is not counted.
            "DASHBOARD_OPT::sub-1",
                "{\"selectedAccount\":{\"optimizationSummary\":{\"totalPotentialSavings\":4.0},\"wastedResources\":["
                + "{\"resourceName\":\"vps\",\"monthlySavings\":13.14},"
                + "{\"resourceName\":\"idle-vm\",\"monthlySavings\":20},"
                + "{\"resourceName\":\"unpriced\",\"monthlySavings\":0,\"savingsUnknown\":true}]}}",
            "AZURE_RIGHTSIZING::sub-1",
                "{\"status\":\"OK\",\"recommendations\":["
                + "{\"resourceName\":\"web\",\"monthlySavings\":7,\"targetSku\":\"Standard_D2s_v5\"},"
                + "{\"resourceName\":\"IDLE-VM\",\"monthlySavings\":9,\"targetSku\":\"Standard_B2s\"}]}"));

        ClientSavingsDashboardDto r = service.getSavings(99L, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 7));

        assertThat(r.getPotentialRightsizingAws()).isEqualTo(2.5);
        assertThat(r.getPotentialOtherAws()).isCloseTo(90.60, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(r.getPotentialRightsizingGcp()).isEqualTo(5.0);
        assertThat(r.getPotentialOtherGcp()).isCloseTo(53.24, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(r.getPotentialRightsizingAzure()).isEqualTo(7.0);
        assertThat(r.getPotentialOtherAzure()).isCloseTo(37.14, org.assertj.core.data.Offset.offset(1e-9));
        // Each account's rightsizing + other equals its dashboard figure (Azure adds its idle list).
        assertThat(r.getPotentialTotal()).isCloseTo(93.10 + 58.24 + 7 + 37.14, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(r.getAccounts()).extracting(ClientSavingsDashboardDto.AccountSavings::getAccountId)
            .containsExactly(1L, 2L, 3L);
        assertThat(r.getAccounts().get(0).getPotentialTotal()).isCloseTo(93.10, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(r.getAccounts().get(0).getSnapshotDate()).isEqualTo("2026-11-01");
    }

    @Test
    void otherOptimizations_neverGoesNegative_whenRightsizingIsNewerThanTheDashboard() {
        CloudAccount gcp = makeAccount(2L, "GCP", "my-project");
        when(cloudAccountRepository.findByClientId(99L)).thenReturn(List.of(gcp));
        noRealizedRows();
        snapshots(java.util.Map.of(
            "GCP_RIGHTSIZING::my-project", "[{\"monthlySavings\":40}]",
            "DASHBOARD_OPT::my-project", "{\"selectedAccount\":{\"optimizationSummary\":{\"totalPotentialSavings\":10}}}"));

        ClientSavingsDashboardDto r = service.getSavings(99L, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 7));

        assertThat(r.getPotentialOtherGcp()).isZero();
        assertThat(r.getPotentialTotal()).isEqualTo(40.0);
    }

    @Test
    void accountFilter_scopesEveryFigureToThatAccount() {
        CloudAccount aws1 = makeAccount(1L, "AWS", "111111111111");
        CloudAccount aws2 = makeAccount(2L, "AWS", "222222222222");
        when(cloudAccountRepository.findByClientId(99L)).thenReturn(List.of(aws1, aws2));
        LocalDate start = LocalDate.of(2026, 11, 1);
        LocalDate end = LocalDate.of(2026, 11, 7);
        YearMonth month = YearMonth.of(2026, 11);
        // Client-wide the ledger says 500; account 2's own closed statement is 12, account 1's is 488.
        when(ledgerReadService.getClientSpotSavings(99L, month, month)).thenReturn(new ClientSpotSavings(
            List.of(new ProviderSavings("AWS", usd("500"), usd("0"), usd("0"))),
            List.of(new AccountMonth(1L, "AWS", month, "CLOSED", true, usd("488"), usd("0"), usd("0")),
                    new AccountMonth(2L, "AWS", month, "CLOSED", true, usd("12"), usd("0"), usd("0"))),
            List.of(month), List.of()));
        when(ledgerReadService.getDailyAccrual(aws2, start, end))
            .thenReturn(List.of(new DailyAccrual(start, "AWS", usd("3.5"))));
        snapshots(java.util.Map.of(
            "DASHBOARD_OPT::111111111111", "{\"selectedAccount\":{\"optimizationSummary\":{\"totalPotentialSavings\":100}}}",
            "DASHBOARD_OPT::222222222222", "{\"selectedAccount\":{\"optimizationSummary\":{\"totalPotentialSavings\":30}}}"));

        ClientSavingsDashboardDto r = service.getSavings(99L, 2L, start, end);

        assertThat(r.getSelectedAccountId()).isEqualTo(2L);
        assertThat(r.getAccountCount()).isEqualTo(1);
        assertThat(r.getPotentialOtherAws()).isEqualTo(30.0);
        assertThat(r.getRealizedSpotAws()).isEqualByComparingTo("12");
        assertThat(r.getRealizedSpotMonths()).containsExactly("2026-11");
        assertThat(r.getRealizedTotal()).isEqualTo(12.0);
        assertThat(r.getTimeSeries().get(0).getAwsSpot()).isEqualByComparingTo("3.50");
        assertThat(r.getAccounts()).extracting(ClientSavingsDashboardDto.AccountSavings::getAccountId).containsExactly(2L);
        assertThat(r.getAccounts().get(0).getRealizedTotal()).isEqualTo(12.0);
        assertThat(r.getClientName()).isEqualTo("Acme Corp");
        verify(ledgerReadService, never()).getDailyAccrual(eq(aws1), any(), any());
        verify(cachedDataRepository, never()).findById("DASHBOARD_OPT::111111111111");
    }

    @Test
    void allAccounts_usesTheClientWideLedgerFigures_andPerAccountRowsSumToThem() {
        CloudAccount aws1 = makeAccount(1L, "AWS", "111111111111");
        CloudAccount aws2 = makeAccount(2L, "AWS", "222222222222");
        when(cloudAccountRepository.findByClientId(99L)).thenReturn(List.of(aws1, aws2));
        YearMonth month = YearMonth.of(2026, 11);
        when(ledgerReadService.getClientSpotSavings(any(), any(), any())).thenReturn(new ClientSpotSavings(
            List.of(new ProviderSavings("AWS", usd("500"), usd("0"), usd("0"))),
            List.of(new AccountMonth(1L, "AWS", month, "CLOSED", true, usd("488"), usd("0"), usd("0")),
                    new AccountMonth(2L, "AWS", month, "CLOSED", true, usd("12"), usd("0"), usd("0"))),
            List.of(month), List.of()));

        ClientSavingsDashboardDto r = service.getSavings(99L, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 7));

        assertThat(r.getSelectedAccountId()).isNull();
        assertThat(r.getRealizedSpotAws()).isEqualByComparingTo("500");
        assertThat(r.getAccounts()).extracting(ClientSavingsDashboardDto.AccountSavings::getRealizedTotal)
            .containsExactly(488.0, 12.0);
        verify(ledgerReadService, never()).getDailyAccrual(any(), any(), any());
    }
    @Test
    void accountFilter_rejectsAnAccountOfAnotherClient() {
        when(cloudAccountRepository.findByClientId(99L)).thenReturn(List.of(makeAccount(1L, "AWS", "111111111111")));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                service.getSavings(99L, 555L, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 7)))
            .isInstanceOf(java.util.NoSuchElementException.class);
        verify(cachedDataRepository, never()).findById(anyString());
        verify(redisCacheService, never()).put(anyString(), any(), org.mockito.ArgumentMatchers.anyInt());
    }
}
