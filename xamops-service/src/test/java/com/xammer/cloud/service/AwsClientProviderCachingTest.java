package com.xammer.cloud.service;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.permissions.AwsPermissionInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.athena.AthenaClient;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.pricing.PricingClient;
import software.amazon.awssdk.services.savingsplans.SavingsplansClient;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;
import software.amazon.awssdk.services.sts.model.GetCallerIdentityResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The previously cache-bypassing client methods must now build each AWS SDK v2 client once and
 * reuse it, so repeated calls no longer leak an sdk-ScheduledExecutor thread per invocation.
 * STS is mocked; nothing here reaches AWS. Clients are built lazily and never resolve identity
 * (which would require a real STS call), so STS stays untouched for these paths.
 */
class AwsClientProviderCachingTest {

    private static final String AWS_ID = "012345678901";
    private static final String ROLE_ARN = "arn:aws:iam::012345678901:role/XamOpsReadOnlyRole";
    private static final String EXTERNAL_ID = "3f2b9c1e-7a44-4d0b-9a51-2c6e8f0d1b77";
    private static final String REGION = "us-east-1";

    private StsClient stsClient;
    private AwsClientProvider provider;

    @BeforeEach
    void setUp() {
        stsClient = mock(StsClient.class);
        provider = new AwsClientProvider(stsClient, mock(AwsPermissionInterceptor.class));
    }

    private static CloudAccount account() {
        CloudAccount account = new CloudAccount();
        account.setId(7L);
        account.setProvider("AWS");
        account.setAwsAccountId(AWS_ID);
        account.setRoleArn(ROLE_ARN);
        account.setExternalId(EXTERNAL_ID);
        return account;
    }

    @Test
    void getPricingClient_returnsCachedInstance() {
        PricingClient first = provider.getPricingClient();
        PricingClient second = provider.getPricingClient();

        assertThat(second).isSameAs(first);
    }

    @Test
    void getHostCloudWatchClient_returnsCachedInstance() {
        // The HOST CloudWatch path logs the host account id before building the client.
        when(stsClient.getCallerIdentity()).thenReturn(
                GetCallerIdentityResponse.builder().account(AWS_ID).build());

        CloudWatchClient first = provider.getHostCloudWatchClient(REGION);
        CloudWatchClient second = provider.getHostCloudWatchClient(REGION);

        assertThat(second).isSameAs(first);
    }

    @Test
    void getHostDynamoDbClient_returnsCachedInstance() {
        DynamoDbClient first = provider.getHostDynamoDbClient();
        DynamoDbClient second = provider.getHostDynamoDbClient();

        assertThat(second).isSameAs(first);
    }

    @Test
    void getHostDynamoDbClient_doesNotAssumeRoleEagerly() {
        provider.getHostDynamoDbClient();

        // Creds now refresh lazily inside the StsAssumeRoleCredentialsProvider — there is no
        // per-call stsClient.assumeRole round-trip when the client is built.
        verify(stsClient, never()).assumeRole(any(AssumeRoleRequest.class));
    }

    @Test
    void getRoleAthenaClient_returnsCachedInstance() {
        AthenaClient first = provider.getRoleAthenaClient(ROLE_ARN, EXTERNAL_ID, REGION);
        AthenaClient second = provider.getRoleAthenaClient(ROLE_ARN, EXTERNAL_ID, REGION);

        assertThat(second).isSameAs(first);
    }

    @Test
    void getSavingsplansClient_returnsCachedInstance() {
        CloudAccount account = account();

        SavingsplansClient first = provider.getSavingsplansClient(account, REGION);
        SavingsplansClient second = provider.getSavingsplansClient(account, REGION);

        assertThat(second).isSameAs(first);
    }
}
