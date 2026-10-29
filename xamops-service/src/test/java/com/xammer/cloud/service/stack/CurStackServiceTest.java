package com.xammer.cloud.service.stack;

import com.xammer.cloud.domain.AwsBillingSource;
import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.AwsClientProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.cloudformation.model.CreateStackRequest;
import software.amazon.awssdk.services.cloudformation.model.DescribeStacksRequest;
import software.amazon.awssdk.services.cloudformation.model.DescribeStacksResponse;
import software.amazon.awssdk.services.cloudformation.model.Stack;
import software.amazon.awssdk.services.cloudformation.model.UpdateStackRequest;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketResponse;
import software.amazon.awssdk.services.s3.model.PutBucketEncryptionRequest;
import software.amazon.awssdk.services.s3.model.PutBucketLifecycleConfigurationRequest;
import software.amazon.awssdk.services.s3.model.PutPublicAccessBlockRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for the one-click CUR stack flow. Built the same way as
 * {@link com.xammer.cloud.service.AwsAccountServiceCurUrlTest}: positional constructor +
 * {@link ReflectionTestUtils} for the {@code @Value} field, with {@link AwsClientProvider}
 * mocked to hand back mock CloudFormation/S3 clients.
 */
class CurStackServiceTest {

    private static final String CUR_TEMPLATE = "https://s3.example/xamops-cur-report.yml";
    private static final String ACCOUNT_ID = "111122223333";
    private static final String STACK_NAME = "XamOps-CUR-AcmeProd";
    private static final String BUCKET = "xamops-cur-" + ACCOUNT_ID;

    private final AwsClientProvider awsClientProvider = mock(AwsClientProvider.class);
    private final CloudAccountRepository cloudAccountRepository = mock(CloudAccountRepository.class);
    private final CloudFormationClient cfn = mock(CloudFormationClient.class);
    private final S3Client s3 = mock(S3Client.class);

    private CurStackService service;

    @BeforeEach
    void setUp() {
        service = new CurStackService(awsClientProvider, cloudAccountRepository, CUR_TEMPLATE);
        ReflectionTestUtils.setField(service, "curTemplateUrl", CUR_TEMPLATE);
        when(awsClientProvider.getCloudFormationClient(any(CloudAccount.class), any())).thenReturn(cfn);
        when(awsClientProvider.getS3Client(any(CloudAccount.class), any())).thenReturn(s3);
        when(cloudAccountRepository.save(any(CloudAccount.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private CloudAccount ownCurAccount() {
        CloudAccount account = new CloudAccount("Acme Prod", "ext-1", "read-only", mock(Client.class));
        account.setAwsAccountId(ACCOUNT_ID);
        account.setAwsBillingSource(AwsBillingSource.OWN_CUR);
        return account;
    }

    private static CloudFormationException cfnError(String errorCode, String message, int status) {
        return (CloudFormationException) CloudFormationException.builder()
                .awsErrorDetails(AwsErrorDetails.builder().errorCode(errorCode).errorMessage(message).build())
                .statusCode(status)
                .message(message)
                .build();
    }

    private static S3Exception s3Error(String errorCode, String message, int status) {
        return (S3Exception) S3Exception.builder()
                .awsErrorDetails(AwsErrorDetails.builder().errorCode(errorCode).errorMessage(message).build())
                .statusCode(status)
                .message(message)
                .build();
    }

    private static DescribeStacksResponse stackWith(String status) {
        return DescribeStacksResponse.builder()
                .stacks(Stack.builder().stackName(STACK_NAME).stackStatus(status).build())
                .build();
    }

    // ---------------- detect ----------------

    @Test
    void detect_applicable_regardlessOfBillingSource() {
        // The CUR row is offered for every account now, so a non-OWN_CUR billing source must NOT
        // short-circuit to NOT_APPLICABLE — detect goes on to describe the stack like any other.
        when(cfn.describeStacks(any(DescribeStacksRequest.class))).thenReturn(stackWith("CREATE_COMPLETE"));
        CloudAccount account = ownCurAccount();
        account.setAwsBillingSource(AwsBillingSource.XAMMER_MPA_CUR);

        CurStackService.DetectResult result = service.detect(account);

        assertThat(result.state()).isEqualTo(CurStackService.State.PRESENT);
        verify(awsClientProvider).getCloudFormationClient(any(CloudAccount.class), any());
    }

    @Test
    void detect_notApplicable_whenNoCurTemplate() {
        // The only thing that suppresses the CUR row now is a missing environment CUR template.
        ReflectionTestUtils.setField(service, "curTemplateUrl", "");
        CloudAccount account = ownCurAccount();

        CurStackService.DetectResult result = service.detect(account);

        assertThat(result.state()).isEqualTo(CurStackService.State.NOT_APPLICABLE);
        verify(awsClientProvider, never()).getCloudFormationClient(any(CloudAccount.class), any());
    }

    @Test
    void detect_absent_whenStackDoesNotExist() {
        when(cfn.describeStacks(any(DescribeStacksRequest.class)))
                .thenThrow(cfnError("ValidationError",
                        "Stack with id " + STACK_NAME + " does not exist", 400));

        CurStackService.DetectResult result = service.detect(ownCurAccount());

        assertThat(result.state()).isEqualTo(CurStackService.State.ABSENT);
        assertThat(result.stackName()).isEqualTo(STACK_NAME);
    }

    @Test
    void detect_present_whenLiveStack() {
        when(cfn.describeStacks(any(DescribeStacksRequest.class))).thenReturn(stackWith("CREATE_COMPLETE"));

        CurStackService.DetectResult result = service.detect(ownCurAccount());

        assertThat(result.state()).isEqualTo(CurStackService.State.PRESENT);
    }

    @Test
    void detect_noPermission_onAccessDenied() {
        when(cfn.describeStacks(any(DescribeStacksRequest.class)))
                .thenThrow(cfnError("AccessDenied", "User is not authorized", 403));

        CurStackService.DetectResult result = service.detect(ownCurAccount());

        assertThat(result.state()).isEqualTo(CurStackService.State.NO_PERMISSION);
    }

    // ---------------- applyAsync ----------------

    @Test
    void applyAsync_skipped_whenNoCurTemplate() {
        // Billing source no longer gates the apply; only a missing CUR template skips it.
        ReflectionTestUtils.setField(service, "curTemplateUrl", "");
        CloudAccount account = ownCurAccount();
        account.setAwsBillingSource(AwsBillingSource.COST_EXPLORER_ONLY);

        CurStackService.CurApplyResult result = service.applyAsync(account, false);

        assertThat(result.curStatus()).isEqualTo(CurStackService.CurStatus.SKIPPED);
        verify(cfn, never()).createStack(any(CreateStackRequest.class));
    }

    @Test
    void applyAsync_createsBucket_whenNoSuchBucket_thenCreatesStack() {
        when(cfn.describeStacks(any(DescribeStacksRequest.class)))
                .thenThrow(cfnError("ValidationError", "does not exist", 400));
        when(s3.headBucket(any(HeadBucketRequest.class)))
                .thenThrow(s3Error("NoSuchBucket", "The specified bucket does not exist", 404));

        CurStackService.CurApplyResult result = service.applyAsync(ownCurAccount(), true);

        assertThat(result.curStatus()).isEqualTo(CurStackService.CurStatus.CREATE_IN_PROGRESS);
        ArgumentCaptor<CreateBucketRequest> bucket = ArgumentCaptor.forClass(CreateBucketRequest.class);
        verify(s3).createBucket(bucket.capture());
        assertThat(bucket.getValue().bucket()).isEqualTo(BUCKET);
        verify(cfn).createStack(any(CreateStackRequest.class));
    }

    @Test
    void applyAsync_reappliesHardening_evenWhenBucketAlreadyExists() {
        when(cfn.describeStacks(any(DescribeStacksRequest.class))).thenReturn(stackWith("UPDATE_COMPLETE"));
        when(s3.headBucket(any(HeadBucketRequest.class))).thenReturn(HeadBucketResponse.builder().build());

        CurStackService.CurApplyResult result = service.applyAsync(ownCurAccount(), true);

        assertThat(result.curStatus()).isEqualTo(CurStackService.CurStatus.UPDATE_IN_PROGRESS);
        // Reuse path: bucket was not created, but all three protections are still re-applied.
        verify(s3, never()).createBucket(any(CreateBucketRequest.class));
        verify(s3).putPublicAccessBlock(any(PutPublicAccessBlockRequest.class));
        verify(s3).putBucketEncryption(any(PutBucketEncryptionRequest.class));
        verify(s3).putBucketLifecycleConfiguration(any(PutBucketLifecycleConfigurationRequest.class));
        verify(cfn).updateStack(any(UpdateStackRequest.class));
    }

    @Test
    void applyAsync_pendingPermission_whenAccessDeniedAndConnectionRan() {
        when(cfn.describeStacks(any(DescribeStacksRequest.class)))
                .thenThrow(cfnError("AccessDenied", "not authorized", 403));

        CurStackService.CurApplyResult result = service.applyAsync(ownCurAccount(), true);

        assertThat(result.curStatus()).isEqualTo(CurStackService.CurStatus.PENDING_PERMISSION);
        verify(cfn, never()).createStack(any(CreateStackRequest.class));
    }

    @Test
    void applyAsync_noPermissionTerminal_whenAccessDeniedAndConnectionDidNotRun() {
        when(cfn.describeStacks(any(DescribeStacksRequest.class)))
                .thenThrow(cfnError("AccessDenied", "not authorized", 403));

        CurStackService.CurApplyResult result = service.applyAsync(ownCurAccount(), false);

        assertThat(result.curStatus()).isEqualTo(CurStackService.CurStatus.NO_PERMISSION_TERMINAL);
    }

    @Test
    void applyAsync_noChanges_onNoUpdatesMessage() {
        when(cfn.describeStacks(any(DescribeStacksRequest.class))).thenReturn(stackWith("UPDATE_COMPLETE"));
        when(s3.headBucket(any(HeadBucketRequest.class))).thenReturn(HeadBucketResponse.builder().build());
        when(cfn.updateStack(any(UpdateStackRequest.class)))
                .thenThrow(cfnError("ValidationError", "No updates are to be performed.", 400));

        CurStackService.CurApplyResult result = service.applyAsync(ownCurAccount(), true);

        assertThat(result.curStatus()).isEqualTo(CurStackService.CurStatus.NO_CHANGES);
    }

    @Test
    void applyAsync_persistsStackIdentity_onSuccessfulSubmit() {
        when(cfn.describeStacks(any(DescribeStacksRequest.class)))
                .thenThrow(cfnError("ValidationError", "does not exist", 400));
        when(s3.headBucket(any(HeadBucketRequest.class)))
                .thenThrow(s3Error("NoSuchBucket", "does not exist", 404));

        CloudAccount account = ownCurAccount();
        service.applyAsync(account, true);

        ArgumentCaptor<CloudAccount> saved = ArgumentCaptor.forClass(CloudAccount.class);
        verify(cloudAccountRepository).save(saved.capture());
        assertThat(saved.getValue().getCurStackName()).isEqualTo(STACK_NAME);
        assertThat(saved.getValue().getCurStackRegion()).isEqualTo("us-east-1");
    }
}
