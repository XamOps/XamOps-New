package com.xammer.cloud.service.gcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.bigquery.BigQuery;
import com.xammer.cloud.domain.CachedData;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.CspBillingConfig;
import com.xammer.cloud.dto.gcp.GcpCostDto;
import com.xammer.cloud.repository.CachedDataRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.RedisCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CspBillingIngestionServiceTest {

    @Mock CloudAccountRepository cloudAccountRepository;
    @Mock CspBillingQueryService queryService;
    @Mock CachedDataRepository cachedDataRepository;
    @Mock RedisCacheService redisCacheService;
    @Mock BigQuery bigQuery;

    CspBillingIngestionService service;

    CspBillingConfig config;
    CloudAccount account;

    @BeforeEach
    void setUp() {
        service = new CspBillingIngestionService(
            cloudAccountRepository, queryService,
            cachedDataRepository, redisCacheService, new ObjectMapper());

        config = new CspBillingConfig();
        config.setName("Sisl");
        config.setGcpProjectId("sisl-bq-billing");
        config.setDatasetId("Xammer_Billing_Dataset");
        config.setTableId("Xammer_Dataset");
        config.setServiceAccountKey("{}");

        account = new CloudAccount();
        account.setId(1L);
        account.setGcpProjectId("my-project");
        account.setCspManaged(true);
        account.setCspBillingConfig(config);
    }

    @Test
    void ingestForAccount_writesBreakdownToCache() {
        List<GcpCostDto> breakdown = List.of(new GcpCostDto("Compute Engine", 12.50, false));
        List<GcpCostDto> regions = List.of(new GcpCostDto("asia-south1", 12.50, false));
        List<GcpCostDto> history = List.of(new GcpCostDto("2026-04", 12.50, false));

        when(queryService.buildClient(config)).thenReturn(bigQuery);
        when(queryService.queryBreakdownByDateRange(eq(bigQuery), eq(config), eq("my-project"),
                eq("service_description"), anyString(), anyString()))
            .thenReturn(breakdown);
        when(queryService.queryBreakdownByDateRange(eq(bigQuery), eq(config), eq("my-project"),
                eq("region"), anyString(), anyString()))
            .thenReturn(regions);
        when(queryService.queryMonthlyHistory(eq(bigQuery), eq(config), eq("my-project")))
            .thenReturn(history);
        when(queryService.queryTotalByDateRange(eq(bigQuery), eq(config), eq("my-project"),
                anyString(), anyString()))
            .thenReturn(12.50);
        when(cachedDataRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.ingestForAccount(account);

        ArgumentCaptor<CachedData> cacheCaptor = ArgumentCaptor.forClass(CachedData.class);
        verify(cachedDataRepository, atLeast(3)).save(cacheCaptor.capture());

        List<String> savedKeys = cacheCaptor.getAllValues().stream()
            .map(CachedData::getCacheKey).toList();
        assertThat(savedKeys).anyMatch(k -> k.equals("GCP_COST_BREAKDOWN::my-project::SERVICE"));
        assertThat(savedKeys).anyMatch(k -> k.equals("GCP_COST_BY_REGION::my-project"));
        assertThat(savedKeys).anyMatch(k -> k.equals("GCP_COST_HISTORY::my-project"));
        assertThat(savedKeys).anyMatch(k -> k.equals("gcp:unfiltered-mtd-spend:my-project"));
        // The dashboard's Top Services widget reads this key, not the breakdown key.
        assertThat(savedKeys).anyMatch(k -> k.equals("gcp:billing-summary:my-project"));
    }

    /**
     * Ingestion must filter on usage date, matching GcpCostService's CSP read path. Both
     * write the same cache keys, so a mismatched filter made the dashboard KPI flip between
     * two values depending on which wrote last — and invoice_month undercounts by dropping
     * boundary usage billed to the previous invoice period.
     */
    @Test
    void ingestForAccount_filtersByCurrentMonthUsageDates_notInvoiceMonth() {
        when(queryService.buildClient(config)).thenReturn(bigQuery);
        when(queryService.queryBreakdownByDateRange(any(), any(), any(), any(), any(), any()))
            .thenReturn(List.of());
        when(queryService.queryMonthlyHistory(any(), any(), any())).thenReturn(List.of());
        when(queryService.queryTotalByDateRange(any(), any(), any(), any(), any())).thenReturn(0.0);
        when(cachedDataRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.ingestForAccount(account);

        java.time.LocalDate today = java.time.LocalDate.now();
        String expectedStart = today.withDayOfMonth(1).toString();
        String expectedEnd = today.toString();

        verify(queryService).queryTotalByDateRange(bigQuery, config, "my-project", expectedStart, expectedEnd);
        verify(queryService).queryBreakdownByDateRange(
            bigQuery, config, "my-project", "service_description", expectedStart, expectedEnd);
        verify(queryService).queryBreakdownByDateRange(
            bigQuery, config, "my-project", "region", expectedStart, expectedEnd);

        // The invoice_month-based variants must no longer be used.
        verify(queryService, never()).queryMtdTotal(any(), any(), any(), any());
        verify(queryService, never()).queryServiceBreakdown(any(), any(), any(), any());
        verify(queryService, never()).queryRegionBreakdown(any(), any(), any(), any());
    }

    @Test
    void ingestForAccountId_skipsUnknownAccount() {
        when(cloudAccountRepository.findById(99L)).thenReturn(Optional.empty());

        service.ingestForAccountId(99L);

        verify(queryService, never()).buildClient(any());
        verify(cachedDataRepository, never()).save(any());
    }

    @Test
    void ingestForAccount_skipsAccountWithNullConfig() {
        account.setCspBillingConfig(null);

        service.ingestForAccount(account);

        verify(queryService, never()).buildClient(any());
        verify(cachedDataRepository, never()).save(any());
    }

    @Test
    void ingestAll_processesAllManagedAccounts() {
        when(cloudAccountRepository.findByCspManagedTrue()).thenReturn(List.of(account));
        // ingestAll re-reads each account by id so the LAZY cspBillingConfig resolves
        // inside the transaction — the scheduled job has no open session otherwise.
        when(cloudAccountRepository.findById(1L)).thenReturn(Optional.of(account));
        when(queryService.buildClient(config)).thenReturn(bigQuery);
        when(queryService.queryBreakdownByDateRange(any(), any(), any(), any(), any(), any()))
            .thenReturn(List.of());
        when(queryService.queryMonthlyHistory(any(), any(), any())).thenReturn(List.of());
        when(queryService.queryTotalByDateRange(any(), any(), any(), any(), any())).thenReturn(0.0);
        when(cachedDataRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.ingestAll();

        verify(cloudAccountRepository).findByCspManagedTrue();
        verify(queryService).buildClient(config);
    }
}
