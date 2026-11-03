package com.xammer.cloud.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.config.DbOpsFeatureFlags;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.dbops.DbInstanceDto;
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
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * COR-13 (failure-vs-empty contract): a wholly-failed AWS inventory scan must complete
 * exceptionally and must NOT be cached; a scan that genuinely returned zero instances IS cached.
 */
class DbOpsCostServiceInventoryFailureTest {

    private static final String AWS_ID = "111111111111";
    private static final String INVENTORY_KEY = "dbops:aws:inventory:" + AWS_ID;

    private CloudAccountRepository cloudAccountRepository;
    private AwsClientProvider awsClientProvider;
    private RedisCacheService redisCache;
    private CachedDataRepository cachedDataRepository;
    private DbOpsCostService service;
    private CloudAccount account;

    @BeforeEach
    void setUp() {
        cloudAccountRepository = mock(CloudAccountRepository.class);
        awsClientProvider = mock(AwsClientProvider.class);
        redisCache = mock(RedisCacheService.class);
        cachedDataRepository = mock(CachedDataRepository.class);
        account = new CloudAccount();
        account.setId(1L);
        account.setAwsAccountId(AWS_ID);
        account.setProvider("AWS");
        when(cloudAccountRepository.findByAwsAccountId(AWS_ID)).thenReturn(List.of(account));
        service = new DbOpsCostService(cloudAccountRepository, awsClientProvider, redisCache,
                cachedDataRepository, new ObjectMapper(), mock(AgentDataCacheRepository.class),
                mock(KubernetesClusterConfigRepository.class), mock(DbAgentConfigRepository.class),
                mock(DbAgentDataCacheRepository.class), mock(DbAgentMetricsHistoryRepository.class),
                mock(SpotPricingService.class), mock(DbOpsFeatureFlags.class));
    }

    private void stubSingleRegion() {
        Ec2Client ec2 = mock(Ec2Client.class);
        when(awsClientProvider.getEc2Client(eq(account), anyString())).thenReturn(ec2);
        when(ec2.describeRegions()).thenReturn(DescribeRegionsResponse.builder()
                .regions(Region.builder().regionName("us-east-1").optInStatus("opt-in-not-required").build())
                .build());
    }

    @Test
    void whollyFailedScanThrowsAndIsNotCached() {
        // DescribeRegions fails → fall back to the 9 SCAN_REGIONS; every RDS client then denies.
        Ec2Client ec2 = mock(Ec2Client.class);
        when(awsClientProvider.getEc2Client(eq(account), anyString())).thenReturn(ec2);
        when(ec2.describeRegions()).thenThrow(new RuntimeException("no ec2:DescribeRegions"));
        when(awsClientProvider.getRdsClient(eq(account), anyString()))
                .thenThrow(new RuntimeException("AccessDenied"));

        assertThatThrownBy(() -> service.getDbInventory(AWS_ID, true).get())
                .isInstanceOf(ExecutionException.class);

        verify(redisCache, never()).put(eq(INVENTORY_KEY), any(), anyInt());
        verify(cachedDataRepository, never()).save(any());
    }

    @Test
    void genuineEmptyIsCached() throws Exception {
        stubSingleRegion();
        RdsClient rds = mock(RdsClient.class);
        DescribeDBInstancesIterable it = mock(DescribeDBInstancesIterable.class);
        when(it.iterator()).thenReturn(Collections.emptyIterator());
        when(rds.describeDBInstancesPaginator(any(DescribeDbInstancesRequest.class))).thenReturn(it);
        when(awsClientProvider.getRdsClient(eq(account), eq("us-east-1"))).thenReturn(rds);

        List<DbInstanceDto> result = service.getDbInventory(AWS_ID, true).get();

        assertThat(result).isEmpty();
        verify(redisCache).put(eq(INVENTORY_KEY), any(), anyInt());
        verify(cachedDataRepository).save(any());
    }
}
