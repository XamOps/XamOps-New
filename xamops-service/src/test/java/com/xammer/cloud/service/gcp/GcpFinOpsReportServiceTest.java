package com.xammer.cloud.service.gcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.gcp.GcpFinOpsReportDto;
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

import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Covers the two guards that stop a reseller-billed (CSP-managed) project from having
 * its only copy of cost data deleted and replaced with zeros.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GcpFinOpsReportServiceTest {

    private static final String PROJECT = "trustsignal-prod-320607";
    private static final String REPORT_CACHE_KEY = "gcp:finops-report:" + PROJECT;
    private static final String REPORT_DB_KEY = "GCP_FINOPS::" + PROJECT;

    @Mock GcpCostService gcpCostService;
    @Mock GcpOptimizationService gcpOptimizationService;
    @Mock GcpBudgetService gcpBudgetService;
    @Mock RedisCacheService redisCacheService;
    @Mock CachedDataRepository cachedDataRepository;
    @Mock CloudAccountRepository cloudAccountRepository;
    @Mock CspBillingIngestionService cspBillingIngestionService;

    GcpFinOpsReportService service;

    @BeforeEach
    void setUp() {
        service = new GcpFinOpsReportService(
                gcpCostService, gcpOptimizationService, gcpBudgetService,
                redisCacheService, cachedDataRepository, new ObjectMapper(),
                cloudAccountRepository, cspBillingIngestionService);
    }

    private CloudAccount account(boolean cspManaged) {
        CloudAccount account = new CloudAccount();
        account.setId(204L);
        account.setGcpProjectId(PROJECT);
        account.setCspManaged(cspManaged);
        return account;
    }

    /**
     * The regression that zeroed production. For a CSP-managed project the section caches
     * hold the only copy of the reseller figures, so a force refresh must re-ingest rather
     * than evict — evicting sends every cost getter to a BigQuery table that does not exist
     * for the customer project, which times out and yields zeros.
     */
    @Test
    void refresh_cspManagedProject_reingestsAndDoesNotEvictCostCaches() {
        // Sections stubbed so the rebuild that follows the refresh runs cleanly.
        stubAllSectionsEmpty();
        when(cloudAccountRepository.findByGcpProjectId(PROJECT)).thenReturn(Optional.of(account(true)));

        service.refreshFinOpsReport(PROJECT).join();

        verify(cspBillingIngestionService).ingestForAccountId(204L);
        verify(gcpCostService, never()).evictAllCostCaches(anyString());
        // The assembled report itself is still dropped, so it gets rebuilt.
        verify(redisCacheService).evict(REPORT_CACHE_KEY);
        verify(cachedDataRepository).deleteById(REPORT_DB_KEY);
    }

    @Test
    void refresh_normalProject_stillEvictsCostCaches() {
        stubAllSectionsEmpty();
        when(cloudAccountRepository.findByGcpProjectId(PROJECT)).thenReturn(Optional.of(account(false)));

        service.refreshFinOpsReport(PROJECT).join();

        verify(gcpCostService).evictAllCostCaches(PROJECT);
        verify(cspBillingIngestionService, never()).ingestForAccountId(anyLong());
    }

    /**
     * The 500 regression: a project with no DB snapshot yet (e.g. a zero-cost report that
     * was never persisted) must still refresh cleanly. The guarded delete skips the missing
     * row instead of letting Spring Data's deleteById throw EmptyResultDataAccessException.
     */
    @Test
    void refresh_missingSnapshot_doesNotDeleteOrThrow() {
        stubAllSectionsEmpty();
        when(cloudAccountRepository.findByGcpProjectId(PROJECT)).thenReturn(Optional.of(account(false)));
        when(cachedDataRepository.existsById(REPORT_DB_KEY)).thenReturn(false);

        service.refreshFinOpsReport(PROJECT).join();

        verify(redisCacheService).evict(REPORT_CACHE_KEY);
        verify(cachedDataRepository, never()).deleteById(REPORT_DB_KEY);
    }

    @Test
    void refresh_unknownProject_treatedAsNormal() {
        stubAllSectionsEmpty();
        when(cloudAccountRepository.findByGcpProjectId(PROJECT)).thenReturn(Optional.empty());

        service.refreshFinOpsReport(PROJECT).join();

        verify(gcpCostService).evictAllCostCaches(PROJECT);
        verify(cspBillingIngestionService, never()).ingestForAccountId(anyLong());
    }

    /**
     * A report assembled entirely from timeout defaults must not be snapshotted, or the
     * zeros outlive the failure that produced them.
     */
    @Test
    void generate_reportWithNoCostData_isNotSnapshotted() {
        stubAllSectionsEmpty();

        service.generateFinOpsReportSync(PROJECT);

        verify(cachedDataRepository, never()).save(any());
        verify(redisCacheService).put(eq(REPORT_CACHE_KEY), any(GcpFinOpsReportDto.class), eq(5));
    }

    @Test
    void generate_reportWithCostData_isSnapshotted() {
        stubAllSectionsEmpty();
        // One real signal is enough to mark the report as usable.
        when(gcpCostService.getUnfilteredMonthToDateSpend(PROJECT))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(3748.52));
        when(cachedDataRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.generateFinOpsReportSync(PROJECT);

        verify(redisCacheService).put(eq(REPORT_CACHE_KEY), any(GcpFinOpsReportDto.class), eq(60));
        verify(cachedDataRepository).save(any());
    }

    private void stubAllSectionsEmpty() {
        when(redisCacheService.get(eq(REPORT_CACHE_KEY), eq(GcpFinOpsReportDto.class))).thenReturn(Optional.empty());
        when(cachedDataRepository.findById(anyString())).thenReturn(Optional.empty());
        // Invalidation now guards deleteById behind existsById (see deleteDbSnapshotIfPresent),
        // so the snapshot must report as present for the delete to run.
        when(cachedDataRepository.existsById(anyString())).thenReturn(true);

        when(gcpCostService.getBillingSummary(PROJECT))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(java.util.List.of()));
        when(gcpCostService.getCostByRegion(PROJECT))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(java.util.List.of()));
        when(gcpCostService.getUnfilteredMonthToDateSpend(PROJECT))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(0.0));
        when(gcpCostService.getLastMonthSpend(PROJECT))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(0.0));
        when(gcpCostService.getCostAnomalies(eq(PROJECT), anyBoolean()))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(java.util.List.of()));
        when(gcpBudgetService.getBudgets(PROJECT))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(java.util.List.of()));

        when(gcpOptimizationService.getWasteReport(PROJECT)).thenReturn(java.util.List.of());
        when(gcpOptimizationService.getRightsizingRecommendations(PROJECT)).thenReturn(java.util.List.of());
        when(gcpOptimizationService.getOptimizationSummary(PROJECT))
                .thenReturn(new com.xammer.cloud.dto.DashboardData.OptimizationSummary(0.0, 0));
    }
}
