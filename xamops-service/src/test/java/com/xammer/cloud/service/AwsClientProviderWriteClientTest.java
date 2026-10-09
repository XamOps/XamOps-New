package com.xammer.cloud.service;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.permissions.AwsPermissionInterceptor;
import com.xammer.cloud.service.autospotting.AutoSpottingBlockedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;
import software.amazon.awssdk.services.sts.model.AssumeRoleResponse;
import software.amazon.awssdk.services.sts.model.AssumedRoleUser;
import software.amazon.awssdk.services.sts.model.Credentials;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The AutoSpotting write client must assume only the role ARN and ExternalId stored at
 * onboarding (never a role derived from the account number with the account number as
 * ExternalId), and must refuse read-only or unverified connections before any STS call.
 * STS is mocked; nothing here reaches AWS.
 */
class AwsClientProviderWriteClientTest {

    private static final String AWS_ID = "012345678901";
    private static final String ROLE_ARN = "arn:aws:iam::012345678901:role/XamOpsReadWriteRole-uat";
    private static final String EXTERNAL_ID = "3f2b9c1e-7a44-4d0b-9a51-2c6e8f0d1b77";

    private StsClient stsClient;
    private AwsClientProvider provider;

    @BeforeEach
    void setUp() {
        stsClient = mock(StsClient.class);
        provider = new AwsClientProvider(stsClient, mock(AwsPermissionInterceptor.class));
    }

    private static CloudAccount account(String accessType, String status, String roleArn, String externalId) {
        CloudAccount account = new CloudAccount();
        account.setId(7L);
        account.setProvider("AWS");
        account.setAwsAccountId(AWS_ID);
        account.setAccessType(accessType);
        account.setStatus(status);
        account.setRoleArn(roleArn);
        account.setExternalId(externalId);
        return account;
    }

    private void givenStsReturnsCredentials() {
        when(stsClient.assumeRole(any(AssumeRoleRequest.class))).thenReturn(AssumeRoleResponse.builder()
                .credentials(Credentials.builder()
                        .accessKeyId("test-access-key")
                        .secretAccessKey("test-secret")
                        .sessionToken("test-token")
                        .expiration(Instant.now().plus(1, ChronoUnit.HOURS))
                        .build())
                .assumedRoleUser(AssumedRoleUser.builder()
                        .arn("arn:aws:sts::012345678901:assumed-role/XamOpsReadWriteRole-uat/xamops")
                        .assumedRoleId("AROATEST:xamops")
                        .build())
                .build());
    }

    @Test
    void readWriteConnectedAccount_assumesStoredRoleWithStoredExternalId() {
        givenStsReturnsCredentials();

        AutoScalingClient client = provider.getAutoScalingWriteClient(
                account("read-write", "CONNECTED", ROLE_ARN, EXTERNAL_ID), "us-east-1");

        assertThat(client).isNotNull();
        client.serviceClientConfiguration().credentialsProvider().resolveIdentity().join();

        ArgumentCaptor<AssumeRoleRequest> request = ArgumentCaptor.forClass(AssumeRoleRequest.class);
        verify(stsClient).assumeRole(request.capture());
        assertThat(request.getValue().roleArn()).isEqualTo(ROLE_ARN);
        assertThat(request.getValue().externalId()).isEqualTo(EXTERNAL_ID).isNotEqualTo(AWS_ID);
    }

    @Test
    void secondCall_returnsTheCachedClient() {
        CloudAccount account = account("read-write", "CONNECTED", ROLE_ARN, EXTERNAL_ID);

        AutoScalingClient first = provider.getAutoScalingWriteClient(account, "us-east-1");
        AutoScalingClient second = provider.getAutoScalingWriteClient(account, "us-east-1");

        assertThat(second).isSameAs(first);
        assertThat(provider.getAutoScalingClient(account, "us-east-1")).isSameAs(first);
    }

    @Test
    void readOnlyAccount_isRefusedWithoutStsCall() {
        assertBlocked(account("read-only", "CONNECTED", ROLE_ARN, EXTERNAL_ID),
                AutoSpottingBlockedException.READ_ONLY_CONNECTION);
    }

    @Test
    void nullAccessType_isTreatedAsReadOnly() {
        assertBlocked(account(null, "CONNECTED", ROLE_ARN, EXTERNAL_ID),
                AutoSpottingBlockedException.READ_ONLY_CONNECTION);
    }

    @Test
    void pendingAccount_isNotVerified() {
        assertBlocked(account("read-write", "PENDING", ROLE_ARN, EXTERNAL_ID),
                AutoSpottingBlockedException.CONNECTION_NOT_VERIFIED);
    }

    @Test
    void blankExternalId_isNotVerified() {
        assertBlocked(account("read-write", "CONNECTED", ROLE_ARN, "  "),
                AutoSpottingBlockedException.CONNECTION_NOT_VERIFIED);
    }

    @Test
    void missingRoleArn_isNotVerified() {
        assertBlocked(account("read-write", "CONNECTED", null, EXTERNAL_ID),
                AutoSpottingBlockedException.CONNECTION_NOT_VERIFIED);
    }

    private void assertBlocked(CloudAccount account, String code) {
        assertThatThrownBy(() -> provider.getAutoScalingWriteClient(account, "us-east-1"))
                .isInstanceOfSatisfying(AutoSpottingBlockedException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(code);
                    assertThat(e.getStatus().value()).isEqualTo(409);
                });
        verifyNoInteractions(stsClient);
    }
}
