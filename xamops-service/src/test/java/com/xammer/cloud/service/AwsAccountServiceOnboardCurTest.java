package com.xammer.cloud.service;

import com.xammer.cloud.domain.AwsBillingSource;
import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.VerifyAccountRequest;
import com.xammer.cloud.repository.ClientRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.cur.CurAutoRegistrationService;
import com.xammer.cloud.service.stack.CurStackService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.sts.StsClient;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Change 1 (new accounts default to OWN_CUR and persist it) and Change 2 (the connection-complete
 * paths auto-create the CUR stack by reusing {@link CurStackService#applyAsync}, best-effort and
 * off the request thread). Built via the positional constructor + {@code ReflectionTestUtils} for
 * the {@code @Value} and injected-collaborator fields, following {@link AwsAccountServiceCurUrlTest}.
 * The injected executor runs tasks inline ({@code Runnable::run}) so the async submit is exercised
 * synchronously, and the retry backoff is set to zero so no real sleeps occur.
 */
class AwsAccountServiceOnboardCurTest {

    private static final String CONN_TEMPLATE = "https://s3.example/xamops-readonly-role.yml";
    private static final String CONN_RW_TEMPLATE = "https://s3.example/xamops-readwrite-role.yml";
    private static final String CUR_TEMPLATE = "https://s3.example/xamops-cur-report.yml";

    private final CloudAccountRepository cloudAccountRepository = mock(CloudAccountRepository.class);
    private final ClientRepository clientRepository = mock(ClientRepository.class);
    private final AwsClientProvider awsClientProvider = mock(AwsClientProvider.class);
    private final CurStackService curStackService = mock(CurStackService.class);

    private AwsAccountService service;

    @BeforeEach
    void setUp() {
        StsClient stsClient = mock(StsClient.class);
        service = new AwsAccountService(cloudAccountRepository, clientRepository,
                awsClientProvider, stsClient, mock(CacheService.class),
                mock(ApplicationEventPublisher.class),
                mock(CurAutoRegistrationService.class));

        ReflectionTestUtils.setField(service, "cloudFormationTemplateUrl", CONN_TEMPLATE);
        ReflectionTestUtils.setField(service, "cloudFormationReadWriteTemplateUrl", CONN_RW_TEMPLATE);
        ReflectionTestUtils.setField(service, "curTemplateUrl", CUR_TEMPLATE);
        ReflectionTestUtils.setField(service, "readOnlyRoleName", "XamOpsReadOnlyRole");
        ReflectionTestUtils.setField(service, "readWriteRoleName", "XamOpsReadWriteRole");

        // Reused CUR machinery + executor that runs the task inline, so the submit path is tested
        // synchronously. Backoff zeroed so PENDING_PERMISSION retries do not actually sleep.
        ReflectionTestUtils.setField(service, "curStackService", curStackService);
        ReflectionTestUtils.setField(service, "stackSequenceExecutor", (Executor) Runnable::run);
        ReflectionTestUtils.setField(service, "onboardCurRetryBackoffMs", 0L);

        when(clientRepository.findById(anyLong())).thenReturn(Optional.of(mock(Client.class)));
        when(cloudAccountRepository.save(any(CloudAccount.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    // ---------- Change 1: onboarding billing-source default ----------

    @Test
    void generateUrl_nullBillingSource_defaultsToOwnCur_andRecordsCurStack() throws Exception {
        Map<String, Object> result = service.generateCloudFormationUrl(
                "Acme Prod", "111122223333", "read-only", 1L, "cur-bucket-1", null);

        // The CUR stack is auto-created on connect, so no manual launch URL is returned.
        assertThat(result).doesNotContainKey("curUrl");
        assertThat(savedAccount().getAwsBillingSource()).isEqualTo(AwsBillingSource.OWN_CUR);
        assertThat(savedAccount().getCurStackName()).isEqualTo("XamOps-CUR-AcmeProd");
    }

    @Test
    void generateUrl_blankBillingSource_defaultsToOwnCur() throws Exception {
        Map<String, Object> result = service.generateCloudFormationUrl(
                "Acme Prod", "111122223333", "read-only", 1L, "", "");

        assertThat(result).doesNotContainKey("curUrl");
        assertThat(savedAccount().getAwsBillingSource()).isEqualTo(AwsBillingSource.OWN_CUR);
        assertThat(savedAccount().getCurStackName()).isEqualTo("XamOps-CUR-AcmeProd");
    }

    @Test
    void generateUrl_explicitOwnCur_persistsOwnCur() throws Exception {
        service.generateCloudFormationUrl(
                "Acme Prod", "111122223333", "read-only", 1L, "cur-bucket-1", "OWN_CUR");

        assertThat(savedAccount().getAwsBillingSource()).isEqualTo(AwsBillingSource.OWN_CUR);
    }

    @Test
    void generateUrl_explicitMpaCur_persistsMpaCur_andOmitsCurUrl() throws Exception {
        Map<String, Object> result = service.generateCloudFormationUrl(
                "Acme Member", "111122223333", "read-only", 1L, null, "XAMMER_MPA_CUR");

        assertThat(result).doesNotContainKey("curUrl");
        assertThat(savedAccount().getAwsBillingSource()).isEqualTo(AwsBillingSource.XAMMER_MPA_CUR);
    }

    // ---------- Change 2: auto-CUR trigger on connect ----------

    @Test
    void connectViaCallback_ownCur_triggersApplyAsyncOnce() {
        stubConnectRegionsSucceed();
        CloudAccount pending = pendingAccount(AwsBillingSource.OWN_CUR);
        when(cloudAccountRepository.findByExternalId("ext-1")).thenReturn(Optional.of(pending));
        when(curStackService.applyAsync(any(CloudAccount.class), eq(false)))
                .thenReturn(result(CurStackService.CurStatus.CREATE_IN_PROGRESS));

        CloudAccount connected = service.connectViaCallback("ext-1", "111122223333",
                "arn:aws:iam::111122223333:role/XamOpsReadOnlyRole", null);

        assertThat(connected.getStatus()).isEqualTo("CONNECTED");
        verify(curStackService, times(1)).applyAsync(any(CloudAccount.class), eq(false));
    }

    @Test
    void verifyAccount_ownCur_triggersApplyAsyncOnce() {
        stubConnectRegionsSucceed();
        CloudAccount pending = pendingAccount(AwsBillingSource.OWN_CUR);
        when(cloudAccountRepository.findByExternalId("ext-2")).thenReturn(Optional.of(pending));
        when(curStackService.applyAsync(any(CloudAccount.class), eq(false)))
                .thenReturn(result(CurStackService.CurStatus.CREATE_IN_PROGRESS));

        CloudAccount connected = service.verifyAccount(verifyRequest("ext-2"));

        assertThat(connected.getStatus()).isEqualTo("CONNECTED");
        verify(curStackService, times(1)).applyAsync(any(CloudAccount.class), eq(false));
    }

    @Test
    void connectViaCallback_mpaCur_doesNotTriggerAutoCur() {
        stubConnectRegionsSucceed();
        CloudAccount pending = pendingAccount(AwsBillingSource.XAMMER_MPA_CUR);
        when(cloudAccountRepository.findByExternalId("ext-3")).thenReturn(Optional.of(pending));

        service.connectViaCallback("ext-3", "111122223333",
                "arn:aws:iam::111122223333:role/XamOpsReadOnlyRole", null);

        verify(curStackService, never()).applyAsync(any(CloudAccount.class), any(Boolean.class));
    }

    @Test
    void connectViaCallback_costExplorerOnly_doesNotTriggerAutoCur() {
        stubConnectRegionsSucceed();
        CloudAccount pending = pendingAccount(AwsBillingSource.COST_EXPLORER_ONLY);
        when(cloudAccountRepository.findByExternalId("ext-4")).thenReturn(Optional.of(pending));

        service.connectViaCallback("ext-4", "111122223333",
                "arn:aws:iam::111122223333:role/XamOpsReadOnlyRole", null);

        verify(curStackService, never()).applyAsync(any(CloudAccount.class), any(Boolean.class));
    }

    @Test
    void connectViaCallback_curFailure_doesNotFailOnboarding() {
        stubConnectRegionsSucceed();
        CloudAccount pending = pendingAccount(AwsBillingSource.OWN_CUR);
        when(cloudAccountRepository.findByExternalId("ext-5")).thenReturn(Optional.of(pending));
        when(curStackService.applyAsync(any(CloudAccount.class), eq(false)))
                .thenReturn(result(CurStackService.CurStatus.FAILED));

        CloudAccount connected = service.connectViaCallback("ext-5", "111122223333",
                "arn:aws:iam::111122223333:role/XamOpsReadOnlyRole", null);

        assertThat(connected.getStatus()).isEqualTo("CONNECTED");
        verify(curStackService, times(1)).applyAsync(any(CloudAccount.class), eq(false));
    }

    @Test
    void connectViaCallback_executorRejects_doesNotFailOnboarding() {
        stubConnectRegionsSucceed();
        CloudAccount pending = pendingAccount(AwsBillingSource.OWN_CUR);
        when(cloudAccountRepository.findByExternalId("ext-6")).thenReturn(Optional.of(pending));
        ReflectionTestUtils.setField(service, "stackSequenceExecutor", (Executor) task -> {
            throw new RejectedExecutionException("pool full");
        });

        CloudAccount connected = service.connectViaCallback("ext-6", "111122223333",
                "arn:aws:iam::111122223333:role/XamOpsReadOnlyRole", null);

        assertThat(connected.getStatus()).isEqualTo("CONNECTED");
        verify(curStackService, never()).applyAsync(any(CloudAccount.class), any(Boolean.class));
    }

    @Test
    void connectViaCallback_pendingPermission_retriesThenSucceeds() {
        stubConnectRegionsSucceed();
        CloudAccount pending = pendingAccount(AwsBillingSource.OWN_CUR);
        when(cloudAccountRepository.findByExternalId("ext-7")).thenReturn(Optional.of(pending));
        when(curStackService.applyAsync(any(CloudAccount.class), eq(false)))
                .thenReturn(result(CurStackService.CurStatus.PENDING_PERMISSION))
                .thenReturn(result(CurStackService.CurStatus.CREATE_IN_PROGRESS));

        service.connectViaCallback("ext-7", "111122223333",
                "arn:aws:iam::111122223333:role/XamOpsReadOnlyRole", null);

        // First-run IAM propagation retry: PENDING_PERMISSION then a terminal status.
        verify(curStackService, times(2)).applyAsync(any(CloudAccount.class), eq(false));
    }

    @Test
    void connectViaCallback_noCurTemplate_isNoOp() {
        // applyAsync returns SKIPPED when no CUR template is configured; the trigger still fires
        // (account is OWN_CUR) but the one call is a no-op and stops immediately.
        stubConnectRegionsSucceed();
        CloudAccount pending = pendingAccount(AwsBillingSource.OWN_CUR);
        when(cloudAccountRepository.findByExternalId("ext-8")).thenReturn(Optional.of(pending));
        when(curStackService.applyAsync(any(CloudAccount.class), eq(false)))
                .thenReturn(result(CurStackService.CurStatus.SKIPPED));

        CloudAccount connected = service.connectViaCallback("ext-8", "111122223333",
                "arn:aws:iam::111122223333:role/XamOpsReadOnlyRole", null);

        assertThat(connected.getStatus()).isEqualTo("CONNECTED");
        verify(curStackService, times(1)).applyAsync(any(CloudAccount.class), eq(false));
    }

    // ---------- helpers ----------

    private CloudAccount savedAccount() {
        ArgumentCaptor<CloudAccount> captor = ArgumentCaptor.forClass(CloudAccount.class);
        verify(cloudAccountRepository, atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }

    private CloudAccount pendingAccount(AwsBillingSource source) {
        CloudAccount account = new CloudAccount("Acme Prod", "ext", "read-only", mock(Client.class));
        account.setAwsBillingSource(source);
        return account;
    }

    private void stubConnectRegionsSucceed() {
        Ec2Client ec2 = mock(Ec2Client.class);
        when(awsClientProvider.getEc2Client(any(CloudAccount.class), anyString())).thenReturn(ec2);
        // describeRegions() returning a default mock (non-throwing) is enough to pass verification.
    }

    private VerifyAccountRequest verifyRequest(String externalId) {
        VerifyAccountRequest request = new VerifyAccountRequest();
        request.setExternalId(externalId);
        request.setAwsAccountId("111122223333");
        request.setRoleName("XamOpsReadOnlyRole");
        return request;
    }

    private CurStackService.CurApplyResult result(CurStackService.CurStatus status) {
        return new CurStackService.CurApplyResult(status, "XamOps-CUR-AcmeProd", status.name());
    }
}
