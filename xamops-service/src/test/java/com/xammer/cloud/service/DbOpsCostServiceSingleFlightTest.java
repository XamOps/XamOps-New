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
import org.junit.jupiter.api.AfterEach;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PERF-2: a per-key single-flight means concurrent cold-cache callers share ONE inventory scan.
 * Two getDbInventory calls issued while the first scan is still in flight must return the same
 * future and drive exactly one underlying describeDBInstancesPaginator call.
 */
class DbOpsCostServiceSingleFlightTest {

    private static final String AWS_ID = "111111111111";

    private CloudAccountRepository cloudAccountRepository;
    private AwsClientProvider awsClientProvider;
    private RedisCacheService redisCache;
    private ExecutorService pool;
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

        // Real multi-threaded pool so the first scan can genuinely be in flight while the caller
        // issues the second request (Runnable::run would complete the first scan synchronously).
        pool = Executors.newFixedThreadPool(4);

        service = new DbOpsCostService(cloudAccountRepository, awsClientProvider, redisCache,
                mock(CachedDataRepository.class), new ObjectMapper(), mock(AgentDataCacheRepository.class),
                mock(KubernetesClusterConfigRepository.class), mock(DbAgentConfigRepository.class),
                mock(DbAgentDataCacheRepository.class), mock(DbAgentMetricsHistoryRepository.class),
                mock(SpotPricingService.class), mock(DbOpsFeatureFlags.class), pool);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    @Test
    void concurrentColdCallsShareOneScan() throws Exception {
        Ec2Client ec2 = mock(Ec2Client.class);
        when(awsClientProvider.getEc2Client(eq(account), anyString())).thenReturn(ec2);
        when(ec2.describeRegions()).thenReturn(DescribeRegionsResponse.builder()
                .regions(Region.builder().regionName("us-east-1").optInStatus("opt-in-not-required").build())
                .build());

        AtomicInteger scans = new AtomicInteger(0);
        CountDownLatch release = new CountDownLatch(1);
        RdsClient rds = mock(RdsClient.class);
        DescribeDBInstancesIterable it = mock(DescribeDBInstancesIterable.class);
        when(it.iterator()).thenAnswer(inv -> Collections.emptyIterator());
        // The paginator call records the scan and blocks, so the first scan stays in flight while
        // the caller obtains the second future.
        when(rds.describeDBInstancesPaginator(any(DescribeDbInstancesRequest.class))).thenAnswer(inv -> {
            scans.incrementAndGet();
            release.await();
            return it;
        });
        when(awsClientProvider.getRdsClient(eq(account), anyString())).thenReturn(rds);

        CompletableFuture<List<DbInstanceDto>> f1 = service.getDbInventory(AWS_ID, false);
        CompletableFuture<List<DbInstanceDto>> f2 = service.getDbInventory(AWS_ID, false);

        // Same in-flight future served both callers.
        assertThat(f1).isSameAs(f2);

        release.countDown();
        f1.get(5, TimeUnit.SECONDS);
        f2.get(5, TimeUnit.SECONDS);

        assertThat(scans.get()).isEqualTo(1);
    }
}
