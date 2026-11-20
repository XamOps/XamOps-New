package com.xammer.cloud.service.autospotting.aws;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.permissions.AwsPermissionCatalog;
import com.xammer.cloud.permissions.AwsPermissionInterceptor;
import com.xammer.cloud.permissions.XamOpsExecutionAttributes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;
import software.amazon.awssdk.services.sts.model.AssumeRoleResponse;
import software.amazon.awssdk.services.sts.model.Credentials;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * plan-phase-12.md 12.1: the factory assumes the stored role ARN and ExternalId with the mode's
 * inline session policy under session name {@code xamops-autospotting}, and closes evicted clients.
 * STS is a mock: no real AWS call.
 */
class AwsAutoSpottingClientFactoryTest {

    private final StsClient sts = mock(StsClient.class);
    private final AwsAutoSpottingClientFactory factory =
            new AwsAutoSpottingClientFactory(sts, mock(AwsPermissionInterceptor.class), 4);

    @AfterEach
    void shutdown() {
        factory.shutdown();
    }

    private void stubAssumeRole() {
        when(sts.assumeRole(any(AssumeRoleRequest.class))).thenReturn(AssumeRoleResponse.builder()
                .credentials(Credentials.builder().accessKeyId("AKIAFAKE").secretAccessKey("fake")
                        .sessionToken("tok").expiration(Instant.now().plusSeconds(3600)).build()).build());
    }

    @Test
    void usesTheStoredRoleArnAndExternalIdWithTheReadSessionPolicy() {
        stubAssumeRole();
        CloudAccount account = FakeAws.account();
        AwsCredentialsProvider creds = factory.credentials(account, AwsSessionPolicies.Mode.READ);
        creds.resolveCredentials();

        ArgumentCaptor<AssumeRoleRequest> captor = ArgumentCaptor.forClass(AssumeRoleRequest.class);
        verify(sts).assumeRole(captor.capture());
        AssumeRoleRequest req = captor.getValue();
        assertThat(req.roleArn()).isEqualTo(account.getRoleArn());
        assertThat(req.externalId()).isEqualTo(account.getExternalId());
        assertThat(req.roleSessionName()).isEqualTo("xamops-autospotting");
        assertThat(req.policy()).isEqualTo(new AwsSessionPolicies(account.getRoleArn())
                .serialize(AwsSessionPolicies.Mode.READ));
        assertThat(req.policy()).doesNotContain("UpdateAutoScalingGroup");
    }

    @Test
    void writeModeAssumesWithTheWriteSessionPolicy() {
        stubAssumeRole();
        CloudAccount account = FakeAws.account();
        factory.credentials(account, AwsSessionPolicies.Mode.WRITE).resolveCredentials();
        ArgumentCaptor<AssumeRoleRequest> captor = ArgumentCaptor.forClass(AssumeRoleRequest.class);
        verify(sts).assumeRole(captor.capture());
        assertThat(captor.getValue().policy()).contains("autoscaling:UpdateAutoScalingGroup");
    }

    @Test
    void anAccountWithoutAStoredRoleIsRefused() {
        CloudAccount account = FakeAws.account();
        account.setRoleArn(null);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> factory.credentials(account, AwsSessionPolicies.Mode.READ))
                .isInstanceOf(IllegalStateException.class);
        verify(sts, never()).assumeRole(any(AssumeRoleRequest.class));
    }

    @Test
    void timeoutsAreTenAndThirtySecondsForReadsAndFifteenAndFortyFiveForWrites() {
        assertThat(AwsAutoSpottingClientFactory.READ_ATTEMPT_TIMEOUT).hasSeconds(10);
        assertThat(AwsAutoSpottingClientFactory.READ_CALL_TIMEOUT).hasSeconds(30);
        assertThat(AwsAutoSpottingClientFactory.WRITE_ATTEMPT_TIMEOUT).hasSeconds(15);
        assertThat(AwsAutoSpottingClientFactory.WRITE_CALL_TIMEOUT).hasSeconds(45);
    }

    /**
     * Phase 23 defect D-23-01: building real (unmocked) SDK clients through the factory threw
     * UnsupportedOperationException, because the per-client IAM prefix was put on the unmodifiable
     * attributes of {@code toBuilder()}. Building a client makes no AWS call.
     */
    @Test
    void buildsRealReadClientsWithThePerClientPermissionAttributes() {
        CloudAccount account = FakeAws.account();
        AwsReadOnlyView view = factory.view(account, "us-east-1");

        assertThat(view.ec2()).isNotNull();
        var ec2Attrs = view.ec2().serviceClientConfiguration().overrideConfiguration().executionAttributes();
        assertThat(ec2Attrs.getAttribute(XamOpsExecutionAttributes.ACCOUNT_ID)).isEqualTo(account.getAwsAccountId());
        assertThat(ec2Attrs.getAttribute(XamOpsExecutionAttributes.REGION)).isEqualTo("us-east-1");
        assertThat(ec2Attrs.getAttribute(XamOpsExecutionAttributes.IAM_SERVICE_PREFIX))
                .isEqualTo(AwsPermissionCatalog.iamPrefixForClient("Ec2Client", null));
        assertThat(view.iam().serviceClientConfiguration().overrideConfiguration().executionAttributes()
                .getAttribute(XamOpsExecutionAttributes.IAM_SERVICE_PREFIX))
                .isEqualTo(AwsPermissionCatalog.iamPrefixForClient("IamClient", null));
        assertThat(view.ec2().serviceClientConfiguration().overrideConfiguration().apiCallTimeout())
                .contains(AwsAutoSpottingClientFactory.READ_CALL_TIMEOUT);
        verify(sts, never()).assumeRole(any(AssumeRoleRequest.class));
    }

    @Test
    void buildsARealMutatingClientWithTheWriteTimeouts() {
        AwsMutatingClient client = factory.mutatingClient(FakeAws.account(), "us-east-1");
        assertThat(client).isNotNull();
        assertThat(factory.overrideConfig(FakeAws.account(), "us-east-1", AwsSessionPolicies.Mode.WRITE,
                "AutoScalingClient").apiCallTimeout()).contains(AwsAutoSpottingClientFactory.WRITE_CALL_TIMEOUT);
        verify(sts, never()).assumeRole(any(AssumeRoleRequest.class));
    }

    @Test
    void theCacheIsBoundedAndEvictedClientsAreClosed() {
        assertThat(factory.maxCachedClientsForTest()).isEqualTo(4);
        FakeAws fake = new FakeAws();
        AwsReadOnlyView view = fake.view();
        factory.putViewForTest(FakeAws.account(), FakeAws.REGION, view);
        factory.invalidateAllForTest();
        verify(fake.asg).close();
        verify(fake.ec2).close();
        verify(fake.iam).close();
        verify(fake.ecs).close();
    }
}
