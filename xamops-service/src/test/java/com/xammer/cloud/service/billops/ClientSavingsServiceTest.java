package com.xammer.cloud.service.billops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.DailySpotSavings;
import com.xammer.cloud.domain.EbsVolumeAction;
import com.xammer.cloud.dto.ClientSavingsDashboardDto;
import com.xammer.cloud.repository.CachedDataRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.DailySpotSavingsRepository;
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
    @Mock EbsVolumeActionRepository ebsVolumeActionRepository;
    @Mock CachedDataRepository cachedDataRepository;

    @InjectMocks ClientSavingsService service;

    @BeforeEach
    void setUp() {
        service.objectMapper = new ObjectMapper();
        when(redisCacheService.get(anyString(), any(com.fasterxml.jackson.core.type.TypeReference.class)))
            .thenReturn(Optional.empty());
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

    @Test
    void getSavings_sumsSpotSavingsAcrossProviders() {
        CloudAccount aws = makeAccount(1L, "AWS", "123456789012");
        CloudAccount gcp = makeAccount(2L, "GCP", "my-project");

        when(cloudAccountRepository.findByClientId(99L)).thenReturn(List.of(aws, gcp));

        LocalDate start = LocalDate.of(2025, 4, 1);
        LocalDate end = LocalDate.of(2025, 4, 7);

        // Test-only non-legacy rows: production rows are all legacy (ck_dss_legacy).
        DailySpotSavings awsSaving = nonLegacyRow(start, 100.0);
        DailySpotSavings gcpSaving = nonLegacyRow(start, 50.0);

        when(dailySpotSavingsRepository.findByAccountIdAndDateBetweenAndLegacyFalseOrderByDateAsc(1L, start, end))
            .thenReturn(List.of(awsSaving));
        when(dailySpotSavingsRepository.findByAccountIdAndDateBetweenAndLegacyFalseOrderByDateAsc(2L, start, end))
            .thenReturn(List.of(gcpSaving));
        when(ebsVolumeActionRepository.findByAccountIdAndStatusAndInitiatedAtBetween(
            anyLong(), anyString(), any(Instant.class), any(Instant.class)))
            .thenReturn(List.of());
        when(cachedDataRepository.findById(anyString())).thenReturn(Optional.empty());

        ClientSavingsDashboardDto result = service.getSavings(99L, start, end);

        assertThat(result.getRealizedSpotAws()).isEqualTo(100.0);
        assertThat(result.getRealizedSpotGcp()).isEqualTo(50.0);
        assertThat(result.getRealizedTotal()).isEqualTo(150.0);
    }

    @Test
    void getSavings_excludesAzureSpotRows() {
        // Azure AutoSpotting was removed: an Azure account's daily_spot_savings rows must
        // not be read or counted toward realized savings.
        CloudAccount aws = makeAccount(1L, "AWS", "123456789012");
        CloudAccount azure = makeAccount(3L, "Azure", "sub-1");
        when(cloudAccountRepository.findByClientId(99L)).thenReturn(List.of(aws, azure));
        LocalDate start = LocalDate.of(2025, 4, 1);
        LocalDate end = LocalDate.of(2025, 4, 7);
        DailySpotSavings awsSaving = nonLegacyRow(start, 100.0);
        when(dailySpotSavingsRepository.findByAccountIdAndDateBetweenAndLegacyFalseOrderByDateAsc(1L, start, end))
            .thenReturn(List.of(awsSaving));
        when(ebsVolumeActionRepository.findByAccountIdAndStatusAndInitiatedAtBetween(
            anyLong(), anyString(), any(Instant.class), any(Instant.class)))
            .thenReturn(List.of());
        when(cachedDataRepository.findById(anyString())).thenReturn(Optional.empty());
        ClientSavingsDashboardDto result = service.getSavings(99L, start, end);
        assertThat(result.getRealizedSpotAws()).isEqualTo(100.0);
        assertThat(result.getRealizedTotal()).isEqualTo(100.0);
        verify(dailySpotSavingsRepository, never())
            .findByAccountIdAndDateBetweenAndLegacyFalseOrderByDateAsc(eq(3L), any(), any());
        verify(dailySpotSavingsRepository, never())
            .sumLegacyAmount(argThat(ids -> ids.contains(3L)), any(), any());
    }
    @Test
    void getSavings_sumsPotentialRightsizingFromCachedData() throws Exception {
        CloudAccount gcp = makeAccount(2L, "GCP", "my-project");
        when(cloudAccountRepository.findByClientId(99L)).thenReturn(List.of(gcp));

        LocalDate start = LocalDate.of(2025, 4, 1);
        LocalDate end = LocalDate.of(2025, 4, 7);

        when(dailySpotSavingsRepository.findByAccountIdAndDateBetweenAndLegacyFalseOrderByDateAsc(anyLong(), any(), any()))
            .thenReturn(List.of());

        String rightsizingJson = "[{\"monthlySavings\": 200.0}, {\"monthlySavings\": 150.0}]";
        com.xammer.cloud.domain.CachedData cd = new com.xammer.cloud.domain.CachedData();
        cd.setCacheKey("GCP_RIGHTSIZING::my-project");
        cd.setJsonData(rightsizingJson);
        cd.setLastUpdated(java.time.LocalDateTime.now());

        when(cachedDataRepository.findById("GCP_RIGHTSIZING::my-project")).thenReturn(Optional.of(cd));

        ClientSavingsDashboardDto result = service.getSavings(99L, start, end);

        assertThat(result.getPotentialRightsizingGcp()).isEqualTo(350.0);
    }

    @Test
    void getSavings_returnsZeroWhenNoCachedData() {
        CloudAccount aws = makeAccount(1L, "AWS", "123456789012");
        when(cloudAccountRepository.findByClientId(99L)).thenReturn(List.of(aws));

        when(dailySpotSavingsRepository.findByAccountIdAndDateBetweenAndLegacyFalseOrderByDateAsc(anyLong(), any(), any()))
            .thenReturn(List.of());
        when(ebsVolumeActionRepository.findByAccountIdAndStatusAndInitiatedAtBetween(
            anyLong(), anyString(), any(Instant.class), any(Instant.class)))
            .thenReturn(List.of());
        when(cachedDataRepository.findById(anyString())).thenReturn(Optional.empty());

        LocalDate start = LocalDate.now().minusDays(7);
        LocalDate end = LocalDate.now();

        ClientSavingsDashboardDto result = service.getSavings(99L, start, end);

        assertThat(result.getGrandTotal()).isEqualTo(0.0);
        assertThat(result.getTimeSeries()).isNotEmpty();
    }

    @Test
    void getSavings_timeSeriesHasOneEntryPerDay() {
        when(cloudAccountRepository.findByClientId(99L)).thenReturn(List.of());

        LocalDate start = LocalDate.of(2025, 4, 1);
        LocalDate end = LocalDate.of(2025, 4, 7);

        ClientSavingsDashboardDto result = service.getSavings(99L, start, end);

        assertThat(result.getTimeSeries()).hasSize(7);
        assertThat(result.getTimeSeries().get(0).getDate()).isEqualTo("2025-04-01");
        assertThat(result.getTimeSeries().get(6).getDate()).isEqualTo("2025-04-07");
    }

    /** A row only a test can hold: every production row is legacy (ck_dss_legacy). */
    private static DailySpotSavings nonLegacyRow(LocalDate date, double amount) {
        DailySpotSavings row = new DailySpotSavings();
        row.setDate(date);
        row.setAmountSaved(amount);
        row.setLegacy(false);
        return row;
    }

    @Test
    void legacyRows_goOnlyToTheLegacyFields_neverToRealizedOrTheSeries() {
        CloudAccount aws = makeAccount(1L, "AWS", "123456789012");
        CloudAccount gcp = makeAccount(2L, "GCP", "my-project");
        when(cloudAccountRepository.findByClientId(99L)).thenReturn(List.of(aws, gcp));
        LocalDate start = LocalDate.of(2025, 4, 1);
        LocalDate end = LocalDate.of(2025, 4, 7);

        // Today every row is legacy, so the realized finder returns nothing.
        when(dailySpotSavingsRepository.findByAccountIdAndDateBetweenAndLegacyFalseOrderByDateAsc(anyLong(), any(), any()))
            .thenReturn(List.of());
        when(dailySpotSavingsRepository.sumLegacyAmount(List.of(1L), start, end)).thenReturn(120.456);
        when(dailySpotSavingsRepository.sumLegacyAmount(List.of(2L), start, end)).thenReturn(30.0);
        when(ebsVolumeActionRepository.findByAccountIdAndStatusAndInitiatedAtBetween(
            anyLong(), anyString(), any(Instant.class), any(Instant.class)))
            .thenReturn(List.of());
        when(cachedDataRepository.findById(anyString())).thenReturn(Optional.empty());

        ClientSavingsDashboardDto result = service.getSavings(99L, start, end);

        assertThat(result.getLegacySpotUnverifiedAws()).isEqualByComparingTo(new BigDecimal("120.46"));
        assertThat(result.getLegacySpotUnverifiedGcp()).isEqualByComparingTo(new BigDecimal("30.00"));
        assertThat(result.getRealizedSpotAws()).isZero();
        assertThat(result.getRealizedSpotGcp()).isZero();
        assertThat(result.getRealizedTotal()).isZero();
        assertThat(result.getGrandTotal()).isZero();
        assertThat(result.getTimeSeries()).hasSize(7).allSatisfy(p -> {
            assertThat(p.getAwsSpot()).isZero();
            assertThat(p.getGcpSpot()).isZero();
        });
        // The finder that returns legacy rows too is never used for a BillOps figure.
        verify(dailySpotSavingsRepository, never()).findByAccountIdAndDateBetweenOrderByDateAsc(any(), any(), any());
    }

    @Test
    void nonLegacyFixtureRow_addsToRealizedAndTheSeries_whileLegacyStaysApart() {
        CloudAccount aws = makeAccount(1L, "AWS", "123456789012");
        when(cloudAccountRepository.findByClientId(99L)).thenReturn(List.of(aws));
        LocalDate start = LocalDate.of(2025, 4, 1);
        LocalDate end = LocalDate.of(2025, 4, 7);

        when(dailySpotSavingsRepository.findByAccountIdAndDateBetweenAndLegacyFalseOrderByDateAsc(1L, start, end))
            .thenReturn(List.of(nonLegacyRow(start.plusDays(2), 80.0)));
        when(dailySpotSavingsRepository.sumLegacyAmount(List.of(1L), start, end)).thenReturn(500.0);
        when(ebsVolumeActionRepository.findByAccountIdAndStatusAndInitiatedAtBetween(
            anyLong(), anyString(), any(Instant.class), any(Instant.class)))
            .thenReturn(List.of());
        when(cachedDataRepository.findById(anyString())).thenReturn(Optional.empty());

        ClientSavingsDashboardDto result = service.getSavings(99L, start, end);

        assertThat(result.getRealizedSpotAws()).isEqualTo(80.0);
        assertThat(result.getRealizedTotal()).isEqualTo(80.0);
        assertThat(result.getGrandTotal()).isEqualTo(80.0);
        assertThat(result.getLegacySpotUnverifiedAws()).isEqualByComparingTo(new BigDecimal("500.00"));
        assertThat(result.getLegacySpotUnverifiedGcp()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.getTimeSeries().get(2).getAwsSpot()).isEqualTo(80.0);
        assertThat(result.getTimeSeries().stream().mapToDouble(ClientSavingsDashboardDto.DailyPoint::getAwsSpot).sum())
            .isEqualTo(80.0);
    }
}
