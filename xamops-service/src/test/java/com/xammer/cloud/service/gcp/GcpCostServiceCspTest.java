package com.xammer.cloud.service.gcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.bigquery.BigQuery;
import com.xammer.cloud.domain.CachedData;
import com.xammer.cloud.domain.CspBillingConfig;
import com.xammer.cloud.dto.gcp.GcpCostDto;
import com.xammer.cloud.repository.CachedDataRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.RedisCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Covers reseller (CSP) routing in the cost getters, and the guard that stops an empty
 * result from being persisted as permanent truth.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GcpCostServiceCspTest {

    private static final String CSP_PROJECT = "trustsignal-prod-320607";
    private static final String OWN_EXPORT_PROJECT = "xamops";

    @Mock GcpClientProvider gcpClientProvider;
    @Mock RedisCacheService redisCacheService;
    @Mock CloudAccountRepository cloudAccountRepository;
    @Mock CachedDataRepository cachedDataRepository;
    @Mock GcpCostDataSnapshotService gcpCostDataSnapshotService;
    @Mock CspBillingQueryService cspBillingQueryService;
    @Mock BigQuery cspClient;

    GcpCostService service;
    CspBillingConfig config;

    @BeforeEach
    void setUp() {
        service = new GcpCostService(gcpClientProvider, redisCacheService, cloudAccountRepository,
                cachedDataRepository, new ObjectMapper(), gcpCostDataSnapshotService,
                cspBillingQueryService);

        config = new CspBillingConfig();
        config.setName("SISL");
        config.setGcpProjectId("sisl-bq-billing");
        config.setDatasetId("Xammer_Billing_Dataset");
        config.setTableId("Xammer_Dataset");
        config.setServiceAccountKey("{}");

        // Cold caches by default.
        when(redisCacheService.get(anyString(), any(TypeReference.class))).thenReturn(Optional.empty());
        when(redisCacheService.get(anyString(), any(Class.class))).thenReturn(Optional.empty());
        when(cachedDataRepository.findById(anyString())).thenReturn(Optional.empty());
        when(cachedDataRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private void givenCspManaged() {
        when(cloudAccountRepository.findCspBillingConfigByGcpProjectId(CSP_PROJECT))
                .thenReturn(Optional.of(config));
        when(cspBillingQueryService.buildClient(config)).thenReturn(cspClient);
    }

    /**
     * The Cost page fix: a date-ranged breakdown for a CSP project must read the reseller
     * dataset. Previously it fell through to the customer project's non-existent billing
     * export and returned an empty list.
     */
    @Test
    void dateRangedBreakdown_cspProject_readsResellerDataset() {
        givenCspManaged();
        when(cspBillingQueryService.queryBreakdownByDateRange(eq(cspClient), eq(config), eq(CSP_PROJECT),
                eq("service_description"), eq("2026-05-08"), eq("2026-08-05")))
                .thenReturn(List.of(new GcpCostDto("Cloud Pub/Sub", 2290.17, false)));

        List<GcpCostDto> result = service.getCostBreakdownSync(
                CSP_PROJECT, "SERVICE", "2026-05-08", "2026-08-05", false);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("Cloud Pub/Sub");
        // Must never touch the customer project's own BigQuery.
        verify(gcpClientProvider, never()).getBigQueryClient(anyString());
    }

    @Test
    void dateRangedBreakdown_regionGrouping_mapsToFlattenedColumn() {
        givenCspManaged();
        when(cspBillingQueryService.queryBreakdownByDateRange(any(), any(), any(),
                eq("region"), any(), any()))
                .thenReturn(List.of(new GcpCostDto("asia-south1", 10.0, false)));

        List<GcpCostDto> result = service.getCostBreakdownSync(
                CSP_PROJECT, "REGION", "2026-08-01", "2026-08-05", false);

        assertThat(result).hasSize(1);
        verify(cspBillingQueryService).queryBreakdownByDateRange(any(), any(), any(),
                eq("region"), eq("2026-08-01"), eq("2026-08-05"));
    }

    @Test
    void mtdSpend_cspProject_readsResellerDataset() {
        givenCspManaged();
        when(cspBillingQueryService.queryTotalByDateRange(eq(cspClient), eq(config), eq(CSP_PROJECT),
                anyString(), anyString())).thenReturn(4414.74);

        Double result = service.getUnfilteredMonthToDateSpendSync(CSP_PROJECT);

        assertThat(result).isEqualTo(4414.74);
        verify(gcpClientProvider, never()).getBigQueryClient(anyString());
    }

    @Test
    void nonCspProject_stillUsesOwnBillingExport() {
        when(cloudAccountRepository.findCspBillingConfigByGcpProjectId(OWN_EXPORT_PROJECT))
                .thenReturn(Optional.empty());
        when(gcpClientProvider.getBigQueryClient(OWN_EXPORT_PROJECT)).thenReturn(Optional.empty());

        List<GcpCostDto> result = service.getCostBreakdownSync(
                OWN_EXPORT_PROJECT, "SERVICE", "2026-08-01", "2026-08-05", false);

        assertThat(result).isEmpty();
        verify(gcpClientProvider).getBigQueryClient(OWN_EXPORT_PROJECT);
        verify(cspBillingQueryService, never()).queryBreakdownByDateRange(any(), any(), any(), any(), any(), any());
    }

    /**
     * The xamops failure mode: an empty result was written to the permanent DB snapshot, and
     * the read path treated any present snapshot as a hit, so the project never retried.
     */
    @Test
    void emptyResult_isNotWrittenToDbSnapshot() {
        givenCspManaged();
        when(cspBillingQueryService.queryBreakdownByDateRange(any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of());

        service.getCostBreakdownSync(CSP_PROJECT, "SERVICE", "2026-08-01", "2026-08-05", false);

        verify(cachedDataRepository, never()).save(any());
        verify(redisCacheService).put(anyString(), anyCollection(), eq(5));
    }

    @Test
    void nonEmptyResult_isWrittenToDbSnapshot() {
        givenCspManaged();
        when(cspBillingQueryService.queryBreakdownByDateRange(any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(new GcpCostDto("BigQuery", 1435.85, false)));

        service.getCostBreakdownSync(CSP_PROJECT, "SERVICE", "2026-08-01", "2026-08-05", false);

        verify(cachedDataRepository).save(any());
        verify(redisCacheService).put(anyString(), anyCollection(), eq(60 * 24));
    }

    @Test
    void emptyDbSnapshot_isIgnoredAndRefetched() throws Exception {
        givenCspManaged();
        // A previously persisted empty result must not short-circuit the fetch.
        CachedData poisoned = new CachedData(
                "GCP_COST_BREAKDOWN_DATE::" + CSP_PROJECT + "::SERVICE::2026-08-01::2026-08-05",
                "[]", LocalDateTime.now());
        when(cachedDataRepository.findById(anyString())).thenReturn(Optional.of(poisoned));
        when(cspBillingQueryService.queryBreakdownByDateRange(any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(new GcpCostDto("Cloud Storage", 22.5, false)));

        List<GcpCostDto> result = service.getCostBreakdownSync(
                CSP_PROJECT, "SERVICE", "2026-08-01", "2026-08-05", false);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("Cloud Storage");
    }
}
