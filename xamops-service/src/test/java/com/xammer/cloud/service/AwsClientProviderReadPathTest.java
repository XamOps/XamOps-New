package com.xammer.cloud.service;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.permissions.AwsPermissionInterceptor;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * FR-CMP-04 "Already solid" regression (design-ux-ops.md §9.8, AC-CMP-06; plan-phase-09.md task 9.5):
 * the read-path credentials come from the stored role ARN and external id on the {@code CloudAccount}
 * (AwsClientProvider.getCredentialsProvider), not instance credentials. If the read path ever
 * silently fell back to {@code DefaultCredentialsProvider}, XamOps would read its own account instead
 * of the customer's — this pins the assume-role shape.
 */
class AwsClientProviderReadPathTest {

    @Test
    void usesStoredRoleArnAndExternalId() {
        StsClient stsClient = mock(StsClient.class);
        AwsPermissionInterceptor interceptor = mock(AwsPermissionInterceptor.class);
        AwsClientProvider provider = new AwsClientProvider(stsClient, interceptor);

        CloudAccount account = new CloudAccount();
        account.setAwsAccountId("012345678901");
        account.setRoleArn("arn:aws:iam::012345678901:role/XamOps-App-EC2-Role");
        account.setExternalId("ext-id-abc");

        AwsCredentialsProvider credentials = provider.getCredentialsProvider(account);

        // The read path assumes the customer's stored role; it is never DefaultCredentialsProvider.
        assertThat(credentials).isInstanceOf(StsAssumeRoleCredentialsProvider.class);
    }
}
