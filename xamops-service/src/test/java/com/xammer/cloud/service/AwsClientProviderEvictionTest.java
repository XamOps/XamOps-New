package com.xammer.cloud.service;

import com.xammer.cloud.permissions.AwsPermissionInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sts.StsClient;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * Evicting an account must route removals through the Caffeine cache so the removal listener fires
 * and closes each evicted {@link AutoCloseable} client. Keys that are not prefixed with the AWS
 * account id (e.g. {@code HOST:}) must be left untouched. STS is mocked; nothing here reaches AWS.
 */
class AwsClientProviderEvictionTest {

    private static final String AWS_ID = "012345678901";

    private AwsClientProvider provider;

    @BeforeEach
    void setUp() {
        provider = new AwsClientProvider(mock(StsClient.class), mock(AwsPermissionInterceptor.class));
    }

    @Test
    void evictAccount_closesMatchingClients() throws Exception {
        AutoCloseable matching = mock(AutoCloseable.class);
        provider.putForTest(AWS_ID + ":FakeClient:us-east-1", matching);

        provider.evictAccount(AWS_ID);

        verify(matching, timeout(1000)).close();
    }

    @Test
    void evictAccount_leavesNonMatchingKeysOpen() throws Exception {
        AutoCloseable hostClient = mock(AutoCloseable.class);
        provider.putForTest("HOST:FakeClient:us-east-1", hostClient);

        provider.evictAccount(AWS_ID);

        verify(hostClient, never()).close();
    }
}
