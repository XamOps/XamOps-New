package com.xammer.cloud.service;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.CloudSecurityFinding;
import com.xammer.cloud.dto.AlertErrorDto;
import com.xammer.cloud.dto.AlertsResponseDto;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.security.SecurityFindingProjectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The AWS alert list assembled from its four sources. The property that matters: one source
 * failing costs only that source. It used to cost everything — the sources are joined with
 * allOf, which fails as soon as any one of them does, and the list was built in a thenApply that
 * a failed allOf skips entirely.
 */
class CloudGuardServiceGetAlertsTest {

    private static final String ACCOUNT_ID = "605134457560";

    @Mock private CloudAccountRepository cloudAccountRepository;
    @Mock private AwsClientProvider awsClientProvider;
    @Mock private RedisCacheService redisCache;
    @Mock private CloudListService cloudListService;
    @Mock private FinOpsService finOpsService;
    @Mock private EmailService emailService;
    @Mock private DashboardDataService dashboardDataService;
    @Mock private AwsSecurityHubService securityHubService;
    @Mock private NotificationRecipientResolver recipientResolver;
    @Mock private AlertNotificationDeduplicator deduplicator;
    @Mock private AlertDigestSeverityGate severityGate;
    @Mock private SecurityFindingProjectionService liveFindings;

    private CloudGuardService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new CloudGuardService(
                cloudAccountRepository, awsClientProvider, redisCache, cloudListService,
                finOpsService, emailService, dashboardDataService, securityHubService,
                new AlertEmailBuilder(), recipientResolver, deduplicator, severityGate);
        ReflectionTestUtils.setField(service, "liveFindings", liveFindings);

        CloudAccount account = mock(CloudAccount.class);
        when(account.getAwsAccountId()).thenReturn(ACCOUNT_ID);
        when(account.getId()).thenReturn(7L);
        when(cloudAccountRepository.findByProviderAccountId(ACCOUNT_ID)).thenReturn(Optional.of(account));

        // No active regions, so the two quota checks complete immediately with nothing to report.
        when(cloudListService.getRegionStatusForAccountOrFail(any(), anyBoolean()))
                .thenReturn(CompletableFuture.completedFuture(List.of()));
        when(liveFindings.asAlerts(anyLong(), anySet())).thenReturn(List.of());

        DashboardData.CostAnomaly spike = new DashboardData.CostAnomaly("anomaly-1", "Amazon EC2", 500,
                LocalDate.now().minusDays(2), LocalDate.now().minusDays(1), 100);
        when(finOpsService.getCostAnomaliesForAlerts(any(), anyBoolean()))
                .thenReturn(CompletableFuture.completedFuture(List.of(spike)));
    }

    @Test
    void oneSourceFailing_keepsTheOthersAndNamesTheFailure() {
        when(securityHubService.getSecurityAlerts(ACCOUNT_ID, true)).thenReturn(CompletableFuture.failedFuture(
                new IllegalStateException("Security Hub could not be read in any region: AccessDeniedException")));

        AlertsResponseDto response = service.getAlerts(ACCOUNT_ID, true).join();

        // The cost spike survives the Security Hub failure.
        assertThat(response.getAlerts()).extracting("type").containsExactly("ANOMALY");
        assertThat(response.getErrors()).hasSize(1);
        AlertErrorDto error = response.getErrors().get(0);
        assertThat(error.getService()).isEqualTo("Security Hub");
        assertThat(error.getErrorType()).isEqualTo("PERMISSION_DENIED");
    }

    @Test
    void securityHubReadDirectly_leavesOutItsLiveCopies() {
        // The same Security Hub finding also arrives through EventBridge; listing both showed it twice.
        when(securityHubService.getSecurityAlerts(ACCOUNT_ID, true))
                .thenReturn(CompletableFuture.completedFuture(List.of()));

        service.getAlerts(ACCOUNT_ID, true).join();

        verify(liveFindings).asAlerts(7L, Set.of(CloudSecurityFinding.SOURCE_SECURITY_HUB));
    }

    @Test
    void securityHubUnreadable_keepsItsLiveCopies() {
        // With the direct read failed, the live copies are the only Security Hub findings left.
        when(securityHubService.getSecurityAlerts(ACCOUNT_ID, true))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("throttled")));

        service.getAlerts(ACCOUNT_ID, true).join();

        verify(liveFindings).asAlerts(7L, Set.of());
    }

    @Test
    void secondRequestWhileAScanRuns_joinsItInsteadOfStartingAnother() {
        CompletableFuture<List<com.xammer.cloud.dto.AlertDto>> securityHub = new CompletableFuture<>();
        when(securityHubService.getSecurityAlerts(ACCOUNT_ID, true)).thenReturn(securityHub);

        CompletableFuture<AlertsResponseDto> first = service.getAlerts(ACCOUNT_ID, true);
        CompletableFuture<AlertsResponseDto> retry = service.getAlerts(ACCOUNT_ID, true);

        assertThat(retry).isSameAs(first);
        verify(securityHubService, times(1)).getSecurityAlerts(ACCOUNT_ID, true);

        securityHub.complete(List.of());
        assertThat(first.join().getAlerts()).hasSize(1);

        // Finished scans are not reused: the next request scans again.
        service.getAlerts(ACCOUNT_ID, true).join();
        verify(securityHubService, times(2)).getSecurityAlerts(ACCOUNT_ID, true);
    }

    @Test
    void regionsUnreadable_reportsTheQuotaChecksAsFailedNotClean() {
        // With expired credentials, discovery used to answer with no regions and both quota checks
        // came back empty — "no limit is close" — instead of failing.
        when(cloudListService.getRegionStatusForAccountOrFail(any(), anyBoolean())).thenReturn(
                CompletableFuture.failedFuture(new IllegalStateException(
                        "Could not list the account's regions: The security token included in the request is expired")));
        when(securityHubService.getSecurityAlerts(ACCOUNT_ID, true))
                .thenReturn(CompletableFuture.completedFuture(List.of()));

        AlertsResponseDto response = service.getAlerts(ACCOUNT_ID, true).join();

        assertThat(response.getErrors()).extracting(AlertErrorDto::getService)
                .containsExactlyInAnyOrder("VPC quotas", "Network quotas");
        assertThat(response.getErrors()).extracting(AlertErrorDto::getErrorType)
                .containsOnly("CREDENTIALS_EXPIRED");
    }

    @Test
    void everySourceHealthy_hasNoErrors() {
        when(securityHubService.getSecurityAlerts(ACCOUNT_ID, true))
                .thenReturn(CompletableFuture.completedFuture(List.of()));

        AlertsResponseDto response = service.getAlerts(ACCOUNT_ID, true).join();

        assertThat(response.getAlerts()).hasSize(1);
        assertThat(response.getErrors()).isEmpty();
        assertThat(response.getMetadata().getCostAnomalyCount()).isEqualTo(1);
    }
}
