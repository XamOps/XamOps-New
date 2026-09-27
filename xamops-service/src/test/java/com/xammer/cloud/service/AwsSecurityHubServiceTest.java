package com.xammer.cloud.service;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.AlertDto;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.repository.CloudAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import software.amazon.awssdk.services.securityhub.SecurityHubClient;
import software.amazon.awssdk.services.securityhub.model.GetFindingsRequest;
import software.amazon.awssdk.services.securityhub.model.GetFindingsResponse;
import software.amazon.awssdk.services.securityhub.model.SecurityHubException;
import software.amazon.awssdk.services.securityhub.model.Severity;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Security Hub as the Alerts page sees it: a source that could not be read anywhere has to be
 * reported as a failure, not as an account with nothing wrong.
 */
class AwsSecurityHubServiceTest {

    private static final String ACCOUNT_ID = "123456789012";

    @Mock private AwsClientProvider awsClientProvider;
    @Mock private RedisCacheService redisCache;
    @Mock private CloudAccountRepository cloudAccountRepository;
    @Mock private CloudListService cloudListService;
    @Mock private SecurityHubClient east;
    @Mock private SecurityHubClient west;

    private AwsSecurityHubService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new AwsSecurityHubService(awsClientProvider, redisCache, cloudAccountRepository, cloudListService);

        CloudAccount account = mock(CloudAccount.class);
        when(cloudAccountRepository.findByAwsAccountId(ACCOUNT_ID)).thenReturn(List.of(account));
        when(redisCache.get(anyString(), any(com.fasterxml.jackson.core.type.TypeReference.class)))
                .thenReturn(Optional.empty());
        when(cloudListService.getRegionStatusForAccountOrFail(any(), anyBoolean()))
                .thenReturn(CompletableFuture.completedFuture(List.of(region("us-east-1"), region("us-west-2"))));
        when(awsClientProvider.getSecurityHubClient(any(), eq("us-east-1"))).thenReturn(east);
        when(awsClientProvider.getSecurityHubClient(any(), eq("us-west-2"))).thenReturn(west);
    }

    @Test
    void accessDeniedInEveryRegion_failsTheAlertsSource() {
        when(east.getFindings(any(GetFindingsRequest.class))).thenThrow(accessDenied());
        when(west.getFindings(any(GetFindingsRequest.class))).thenThrow(accessDenied());

        assertThatThrownBy(() -> service.getSecurityAlerts(ACCOUNT_ID, true).join())
                .isInstanceOf(CompletionException.class)
                .hasMessageContaining("could not be read in any region");
        // A failed scan is not cached, so the next read tries again.
        verify(redisCache, never()).put(anyString(), any(), anyInt());
    }

    @Test
    void accessDeniedInEveryRegion_isStillAnEmptyListForLenientCallers() {
        // The monthly digest reads findings through getSecurityFindings and must not break.
        when(east.getFindings(any(GetFindingsRequest.class))).thenThrow(accessDenied());
        when(west.getFindings(any(GetFindingsRequest.class))).thenThrow(accessDenied());

        assertThat(service.getSecurityFindings(ACCOUNT_ID, true).join()).isEmpty();
    }

    @Test
    void securityHubNotEnabled_isNoFindingsNotAFailure() {
        when(east.getFindings(any(GetFindingsRequest.class))).thenThrow(notSubscribed());
        when(west.getFindings(any(GetFindingsRequest.class))).thenThrow(notSubscribed());

        assertThat(service.getSecurityAlerts(ACCOUNT_ID, true).join()).isEmpty();
    }

    @Test
    void securityHubNotEnabled_reportedWith401_isStillNotAFailure() {
        // The shape AWS actually returns: InvalidAccessException, status 401. The check used to
        // require 400, so an account without Security Hub was reported as Security Hub failing.
        SecurityHubException real = (SecurityHubException) SecurityHubException.builder()
                .message("Account 605134457560 is not subscribed to AWS Security Hub")
                .statusCode(401)
                .build();
        when(east.getFindings(any(GetFindingsRequest.class))).thenThrow(real);
        when(west.getFindings(any(GetFindingsRequest.class))).thenThrow(real);

        assertThat(service.getSecurityAlerts(ACCOUNT_ID, true).join()).isEmpty();
    }

    @Test
    void oneRegionFailing_keepsTheOtherRegionsFindings() {
        when(east.getFindings(any(GetFindingsRequest.class))).thenReturn(page(null, finding("f-1")));
        when(west.getFindings(any(GetFindingsRequest.class))).thenThrow(accessDenied());

        List<AlertDto> alerts = service.getSecurityAlerts(ACCOUNT_ID, true).join();

        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).getType()).isEqualTo("SECURITY");
        assertThat(alerts.get(0).getStatus()).isEqualTo("CRITICAL");
        // The product, not the finding-type path, so the Service column and filter stay readable.
        assertThat(alerts.get(0).getService()).isEqualTo("Security Hub");
    }

    @Test
    void theSameFindingFromTwoRegions_isOneAlert() {
        // Cross-region aggregation: the aggregation region returns the linked region's findings too.
        when(east.getFindings(any(GetFindingsRequest.class))).thenReturn(page(null, finding("f-1")));
        when(west.getFindings(any(GetFindingsRequest.class))).thenReturn(page(null, finding("f-1")));

        assertThat(service.getSecurityAlerts(ACCOUNT_ID, true).join()).hasSize(1);
    }

    @Test
    void findingsBeyondTheFirstPage_areRead() {
        when(east.getFindings(any(GetFindingsRequest.class)))
                .thenReturn(page("token-2", finding("f-1")))
                .thenReturn(page(null, finding("f-2")));
        when(west.getFindings(any(GetFindingsRequest.class))).thenReturn(page(null));

        assertThat(service.getSecurityAlerts(ACCOUNT_ID, true).join()).hasSize(2);
    }

    @Test
    void alertIdsAreStableAcrossScans() {
        when(east.getFindings(any(GetFindingsRequest.class))).thenReturn(page(null, finding("f-1")));
        when(west.getFindings(any(GetFindingsRequest.class))).thenReturn(page(null));

        String first = service.getSecurityAlerts(ACCOUNT_ID, true).join().get(0).getId();
        String second = service.getSecurityAlerts(ACCOUNT_ID, true).join().get(0).getId();

        assertThat(first).isEqualTo(second);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static DashboardData.RegionStatus region(String id) {
        DashboardData.RegionStatus region = new DashboardData.RegionStatus();
        region.setRegionId(id);
        return region;
    }

    private static SecurityHubException accessDenied() {
        return (SecurityHubException) SecurityHubException.builder()
                .message("User is not authorized to perform: securityhub:GetFindings")
                .statusCode(403)
                .build();
    }

    private static SecurityHubException notSubscribed() {
        return (SecurityHubException) SecurityHubException.builder()
                .message("Account is not subscribed to AWS Security Hub")
                .statusCode(400)
                .build();
    }

    private static GetFindingsResponse page(String nextToken,
            software.amazon.awssdk.services.securityhub.model.AwsSecurityFinding... findings) {
        return GetFindingsResponse.builder().findings(findings).nextToken(nextToken).build();
    }

    private static software.amazon.awssdk.services.securityhub.model.AwsSecurityFinding finding(String id) {
        return software.amazon.awssdk.services.securityhub.model.AwsSecurityFinding.builder()
                .id(id)
                .title("S3 bucket is public")
                .description("Bucket allows public read")
                .severity(Severity.builder().label("CRITICAL").build())
                .productName("Security Hub")
                .build();
    }
}
