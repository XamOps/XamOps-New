package com.xammer.cloud.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.CloudListService;
import com.xammer.cloud.service.DashboardDataService;
import com.xammer.cloud.service.ExcelExportService;
import com.xammer.cloud.service.ProwlerService;
import com.xammer.cloud.service.SecurityService;
import com.xammer.cloud.service.security.DetectorCoverageService;
import com.xammer.cloud.service.security.SecurityFindingProjectionService;
import com.xammer.cloud.service.security.SecurityFindingResolutionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins Bug 2's fix: {@code SecurityController.getSecurityFindings} persists its live-scan result
 * under {@code "security-" + accountId} — the same key the cost drawer's Security Center group
 * reads — so a single visit to {@code /security} brings the drawer to parity without waiting for
 * the twice-weekly cron. The cached-hit branch must stay a pure read and never write.
 *
 * <p>The aggregation service's own behaviour is tested separately; here we mock all nine
 * controller dependencies and pin just the snapshot-write wiring on both branches.
 */
@ExtendWith(MockitoExtension.class)
class SecurityControllerSnapshotPersistTest {

    private static final String AWS_ID = "123456789012";
    private static final String CACHE_KEY = "security-" + AWS_ID;

    @Mock private SecurityService securityService;
    @Mock private CloudAccountRepository cloudAccountRepository;
    @Mock private CloudListService cloudListService;
    @Mock private DashboardDataService dashboardDataService;
    @Mock private ExcelExportService excelExportService;
    @Mock private ProwlerService prowlerService;
    @Mock private SecurityFindingProjectionService liveFindings;
    @Mock private DetectorCoverageService detectorCoverage;
    @Mock private SecurityFindingResolutionService resolutions;

    private SecurityController controller;
    private CloudAccount account;

    @BeforeEach
    void setUp() {
        controller = new SecurityController(securityService, cloudAccountRepository, cloudListService,
                dashboardDataService, excelExportService, prowlerService, liveFindings,
                detectorCoverage, resolutions);

        account = new CloudAccount();
        account.setId(42L);
        account.setAwsAccountId(AWS_ID);
        account.setProvider("AWS");
    }

    @Test
    @DisplayName("The live-scan path persists the merged findings under security-<accountId>")
    void liveScanPersistsSnapshot() {
        when(cloudAccountRepository.findByAwsAccountId(AWS_ID)).thenReturn(List.of(account));
        when(cloudListService.getRegionStatusForAccount(eq(account), eq(true)))
                .thenReturn(CompletableFuture.completedFuture(List.of()));

        List<DashboardData.SecurityFinding> scanned = List.of(new DashboardData.SecurityFinding());
        when(securityService.getComprehensiveSecurityFindings(eq(account), anyList(), eq(true)))
                .thenReturn(CompletableFuture.completedFuture(scanned));
        // Pass-through so the persisted list is exactly the scan's output.
        when(liveFindings.mergeInto(anyList(), any()))
                .thenAnswer(inv -> inv.<List<DashboardData.SecurityFinding>>getArgument(0));
        when(resolutions.reconcileAgainstFreshScan(anyList(), any()))
                .thenAnswer(inv -> inv.<List<DashboardData.SecurityFinding>>getArgument(0));

        ResponseEntity<List<DashboardData.SecurityFinding>> response =
                controller.getSecurityFindings(AWS_ID, true).join();

        assertEquals(200, response.getStatusCode().value());
        verify(dashboardDataService).saveSecuritySnapshot(eq(CACHE_KEY), any());
    }

    @Test
    @DisplayName("A young cached hit is served without writing a snapshot")
    void cachedHitDoesNotPersist() {
        when(cloudAccountRepository.findByAwsAccountId(AWS_ID)).thenReturn(List.of(account));

        List<DashboardData.SecurityFinding> cached = List.of(new DashboardData.SecurityFinding());
        when(dashboardDataService.getSecuritySnapshot(eq(CACHE_KEY), any(TypeReference.class), anyInt()))
                .thenReturn(cached);
        lenient().when(liveFindings.mergeInto(anyList(), any()))
                .thenAnswer(inv -> inv.<List<DashboardData.SecurityFinding>>getArgument(0));
        lenient().when(resolutions.filterResolved(anyList(), any()))
                .thenAnswer(inv -> inv.<List<DashboardData.SecurityFinding>>getArgument(0));

        ResponseEntity<List<DashboardData.SecurityFinding>> response =
                controller.getSecurityFindings(AWS_ID, false).join();

        assertEquals(200, response.getStatusCode().value());
        verify(dashboardDataService, never()).saveSecuritySnapshot(anyString(), any());
    }
}
