package com.xammer.cloud.service.azure;

import com.azure.resourcemanager.AzureResourceManager;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.dto.azure.AzureDashboardData;
import com.xammer.cloud.dto.azure.AzureSecurityAssessment;
import com.xammer.cloud.dto.azure.AzureSecurityResponse;
import com.xammer.cloud.dto.azure.AzureWasteItem;
import com.xammer.cloud.repository.CachedDataRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.RedisCacheService;
import com.xammer.cloud.service.security.AzurePrivilegeAnalyzer;
import com.xammer.cloud.service.war.azure.AzureWarRest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Pins the dashboard-payload discipline the fix relies on: the optimization summary and security
 * posture are populated from real scan data, and absence is preserved as absence rather than
 * written as a false zero. These two private builders are the "absence vs false-zero" channel the
 * summary-card fix must not regress, so they are exercised directly via reflection.
 */
class AzureDashboardFindingsServiceTest {

    private static final String SUB = "sub-123";

    private RedisCacheService redisCache;
    private AzureSecurityService azureSecurityService;
    private AzureDashboardService service;

    @BeforeEach
    void setUp() {
        redisCache = Mockito.mock(RedisCacheService.class);
        azureSecurityService = Mockito.mock(AzureSecurityService.class);
        service = new AzureDashboardService(
                Mockito.mock(AzureClientProvider.class),
                Mockito.mock(CloudAccountRepository.class),
                Mockito.mock(CachedDataRepository.class),
                redisCache,
                Mockito.mock(AzureBillingDataIngestionService.class),
                new ObjectMapper(),
                Mockito.mock(AzurePrivilegeAnalyzer.class),
                azureSecurityService,
                Mockito.mock(AzureWarRest.class),
                Mockito.mock(AzureCostAnomalyService.class),
                Runnable::run);
    }

    private void invokeOptimizationSummary(AzureDashboardData data) throws Exception {
        Method m = AzureDashboardService.class.getDeclaredMethod("getAndSetOptimizationSummary",
                AzureResourceManager.class, AzureDashboardData.class, String.class);
        m.setAccessible(true);
        m.invoke(service, null, data, SUB);
    }

    private void invokeSecurityPosture(AzureDashboardData data) throws Exception {
        Method m = AzureDashboardService.class.getDeclaredMethod("getAndSetSecurityPosture",
                AzureDashboardData.class, String.class);
        m.setAccessible(true);
        m.invoke(service, data, SUB);
    }

    @Test
    @DisplayName("(a) waste findings → non-null summary with billable savings and billable count")
    void wasteFindingsProduceOptimizationSummary() throws Exception {
        List<AzureWasteItem> waste = new ArrayList<>();
        waste.add(new AzureWasteItem("1", "disk-a", "Unattached Disk", "eastus", "idle", 10.0));
        waste.add(new AzureWasteItem("2", "vm-b", "Deallocated VM", "eastus", "stopped", 25.5));
        // A free, no-cost-impact hygiene finding must NOT contribute to the reclaimable total.
        waste.add(AzureWasteItem.withNoCostImpact("3", "nic-c", "Orphaned NIC", "eastus", "free"));

        when(redisCache.get(eq(AzureWasteService.CACHE_PREFIX + SUB), any(TypeReference.class)))
                .thenReturn(Optional.of(waste));

        AzureDashboardData data = new AzureDashboardData();
        invokeOptimizationSummary(data);

        assertThat(data.getOptimizationSummary()).isNotNull();
        assertThat(data.getOptimizationSummary().getTotalPotentialSavings()).isEqualTo(35.5);
        assertThat(data.getOptimizationSummary().getCriticalAlertsCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("cold waste cache → optimization summary stays null (no false zero)")
    void coldWasteCacheLeavesSummaryNull() throws Exception {
        when(redisCache.get(eq(AzureWasteService.CACHE_PREFIX + SUB), any(TypeReference.class)))
                .thenReturn(Optional.empty());

        AzureDashboardData data = new AzureDashboardData();
        invokeOptimizationSummary(data);

        assertThat(data.getOptimizationSummary()).isNull();
    }

    @Test
    @DisplayName("(b) OK Defender response → real score and non-empty insights")
    void okSecurityResponseSetsScoreAndInsights() throws Exception {
        List<AzureSecurityAssessment> assessments = new ArrayList<>(List.of(
                new AzureSecurityAssessment("a1", "Enable MFA", "High", "Unhealthy",
                        "r1", "r1", "Identity", "fix"),
                new AzureSecurityAssessment("a2", "Patch VM", "Medium", "Unhealthy",
                        "r2", "r2", "Compute", "fix")));
        AzureSecurityResponse resp = new AzureSecurityResponse(
                AzureSecurityResponse.Status.OK, 45, 90, new ArrayList<>(), assessments);
        when(azureSecurityService.getSecurityData(SUB, false)).thenReturn(resp);

        AzureDashboardData data = new AzureDashboardData();
        invokeSecurityPosture(data);

        // 45 of 90 → 50%.
        assertThat(data.getSecurityScore()).isEqualTo(50);
        assertThat(data.getSecurityScoreUnavailableReason()).isNull();
        assertThat(data.getSecurityInsights()).isNotEmpty();
    }

    @Test
    @DisplayName("PERMISSION_ERROR → unavailable reason set, score left unset (never a number)")
    void permissionErrorSetsUnavailableReason() throws Exception {
        AzureSecurityResponse resp = new AzureSecurityResponse(
                AzureSecurityResponse.Status.PERMISSION_ERROR, 0, 100, new ArrayList<>(), new ArrayList<>());
        when(azureSecurityService.getSecurityData(SUB, false)).thenReturn(resp);

        AzureDashboardData data = new AzureDashboardData();
        invokeSecurityPosture(data);

        assertThat(data.getSecurityScoreUnavailableReason()).isNotBlank();
        assertThat(data.getSecurityScore()).isZero();
    }
}
