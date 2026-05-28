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
        when(queryService.queryServiceBreakdown(eq(bigQuery), eq(config), eq("my-project"), anyString()))
            .thenReturn(breakdown);
        when(queryService.queryRegionBreakdown(eq(bigQuery), eq(config), eq("my-project"), anyString()))
            .thenReturn(regions);
        when(queryService.queryMonthlyHistory(eq(bigQuery), eq(config), eq("my-project")))
            .thenReturn(history);
        when(queryService.queryMtdTotal(eq(bigQuery), eq(config), eq("my-project"), anyString()))
            .thenReturn(12.50);
        when(cachedDataRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.ingestForAccount(account);

        ArgumentCaptor<CachedData> cacheCaptor = ArgumentCaptor.forClass(CachedData.class);
        verify(cachedDataRepository, atLeast(3)).save(cacheCaptor.capture());

        List<String> savedKeys = cacheCaptor.getAllValues().stream()
            .map(CachedData::getCacheKey).toList();
        assertThat(savedKeys).anyMatch(k -> k.startsWith("GCP_COST_BREAKDOWN::my-project"));
        assertThat(savedKeys).anyMatch(k -> k.startsWith("GCP_COST_BY_REGION::my-project"));
        assertThat(savedKeys).anyMatch(k -> k.startsWith("GCP_COST_HISTORY::my-project"));
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
        when(queryService.buildClient(config)).thenReturn(bigQuery);
        when(queryService.queryServiceBreakdown(any(), any(), any(), any())).thenReturn(List.of());
        when(queryService.queryRegionBreakdown(any(), any(), any(), any())).thenReturn(List.of());
        when(queryService.queryMonthlyHistory(any(), any(), any())).thenReturn(List.of());
        when(queryService.queryMtdTotal(any(), any(), any(), any())).thenReturn(0.0);
        when(cachedDataRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.ingestAll();

        verify(cloudAccountRepository).findByCspManagedTrue();
        verify(queryService).buildClient(config);
    }
}
