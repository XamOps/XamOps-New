package com.xammer.cloud.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.config.DbOpsFeatureFlags;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.AgentDataCacheRepository;
import com.xammer.cloud.repository.CachedDataRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.DbAgentConfigRepository;
import com.xammer.cloud.repository.DbAgentDataCacheRepository;
import com.xammer.cloud.repository.DbAgentMetricsHistoryRepository;
import com.xammer.cloud.repository.KubernetesClusterConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeRegionsResponse;
import software.amazon.awssdk.services.ec2.model.Region;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.DescribeDbInstancesRequest;
import software.amazon.awssdk.services.rds.paginators.DescribeDBInstancesIterable;

import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * COR-20: {@link DbOpsCostService} must enumerate the account's enabled regions via
 * EC2 DescribeRegions, skipping {@code not-opted-in} regions, and fall back to the fixed
 * nine SCAN_REGIONS when DescribeRegions is unavailable.
 */
class DbOpsCostServiceRegionsTest {

    private static final String AWS_ID = "111111111111";
    private static final List<String> FALLBACK_REGIONS = List.of(
            "us-east-1", "us-east-2", "us-west-1", "us-west-2",
            "eu-west-1", "eu-central-1", "ap-south-1", "ap-southeast-1", "ap-northeast-1");

    private CloudAccountRepository cloudAccountRepository;
    private AwsClientProvider awsClientProvider;
    private RedisCacheService redisCache;
    private DbOpsCostService service;
    private CloudAccount account;

    @BeforeEach
    void setUp() {
        cloudAccountRepository = mock(CloudAccountRepository.class);
        awsClientProvider = mock(AwsClientProvider.class);
        redisCache = mock(RedisCacheService.class);
        account = new CloudAccount();
        account.setId(1L);
        account.setAwsAccountId(AWS_ID);
        account.setProvider("AWS");
        when(cloudAccountRepository.findByAwsAccountId(AWS_ID)).thenReturn(List.of(account));
        service = new DbOpsCostService(cloudAccountRepository, awsClientProvider, redisCache,
                mock(CachedDataRepository.class), new ObjectMapper(), mock(AgentDataCacheRepository.class),
                mock(KubernetesClusterConfigRepository.class), mock(DbAgentConfigRepository.class),
                mock(DbAgentDataCacheRepository.class), mock(DbAgentMetricsHistoryRepository.class),
                mock(SpotPricingService.class), mock(DbOpsFeatureFlags.class));
    }

    private RdsClient emptyRds() {
        RdsClient rds = mock(RdsClient.class);
        DescribeDBInstancesIterable it = mock(DescribeDBInstancesIterable.class);
        when(it.iterator()).thenReturn(Collections.emptyIterator());
        when(rds.describeDBInstancesPaginator(any(DescribeDbInstancesRequest.class))).thenReturn(it);
        return rds;
    }

    @Test
    void scansOnlyOptedInEnumeratedRegions() throws Exception {
        Ec2Client ec2 = mock(Ec2Client.class);
        when(awsClientProvider.getEc2Client(eq(account), anyString())).thenReturn(ec2);
        when(ec2.describeRegions()).thenReturn(DescribeRegionsResponse.builder()
                .regions(
                        Region.builder().regionName("eu-west-2").optInStatus("opt-in-not-required").build(),
                        Region.builder().regionName("ap-southeast-2").optInStatus("opt-in-not-required").build(),
                        Region.builder().regionName("me-south-1").optInStatus("not-opted-in").build())
                .build());
        when(awsClientProvider.getRdsClient(eq(account), anyString())).thenAnswer(inv -> emptyRds());

        service.getDbInventory(AWS_ID, true).get();

        verify(awsClientProvider).getRdsClient(account, "eu-west-2");
        verify(awsClientProvider).getRdsClient(account, "ap-southeast-2");
        verify(awsClientProvider, never()).getRdsClient(account, "me-south-1");
    }

    @Test
    void fallsBackToScanRegionsWhenDescribeRegionsFails() throws Exception {
        Ec2Client ec2 = mock(Ec2Client.class);
        when(awsClientProvider.getEc2Client(eq(account), anyString())).thenReturn(ec2);
        when(ec2.describeRegions()).thenThrow(new RuntimeException("no ec2:DescribeRegions"));
        when(awsClientProvider.getRdsClient(eq(account), anyString())).thenAnswer(inv -> emptyRds());

        service.getDbInventory(AWS_ID, true).get();

        for (String r : FALLBACK_REGIONS) {
            verify(awsClientProvider).getRdsClient(account, r);
        }
    }
}
