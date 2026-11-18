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
}
