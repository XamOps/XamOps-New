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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeRegionsResponse;
import software.amazon.awssdk.services.ec2.model.Region;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.DescribeDbInstancesRequest;
import software.amazon.awssdk.services.rds.paginators.DescribeDBInstancesIterable;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PERF-1: DbOps cloud I/O must run on the dedicated {@code dbOpsTaskExecutor} pool (thread prefix
 * {@code dbops-io-}), not on {@code ForkJoinPool.commonPool()}. Proven by capturing the worker
 * thread name the RDS client call executes on.
 */
class DbOpsCostServiceExecutorTest {

    private static final String AWS_ID = "111111111111";

    private CloudAccountRepository cloudAccountRepository;
    private AwsClientProvider awsClientProvider;
    private RedisCacheService redisCache;
    private ThreadPoolTaskExecutor executor;
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

        // A real bounded pool with the production thread prefix, so the captured thread name proves
        // the work left the common ForkJoinPool.
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("dbops-io-");
        executor.initialize();

        service = new DbOpsCostService(cloudAccountRepository, awsClientProvider, redisCache,
                mock(CachedDataRepository.class), new ObjectMapper(), mock(AgentDataCacheRepository.class),
                mock(KubernetesClusterConfigRepository.class), mock(DbAgentConfigRepository.class),
                mock(DbAgentDataCacheRepository.class), mock(DbAgentMetricsHistoryRepository.class),
                mock(SpotPricingService.class), mock(DbOpsFeatureFlags.class), executor);
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    @Test
    void inventoryScanRunsOnDedicatedDbOpsPool() throws Exception {
        Ec2Client ec2 = mock(Ec2Client.class);
        when(awsClientProvider.getEc2Client(eq(account), anyString())).thenReturn(ec2);
        when(ec2.describeRegions()).thenReturn(DescribeRegionsResponse.builder()
                .regions(Region.builder().regionName("us-east-1").optInStatus("opt-in-not-required").build())
                .build());

        AtomicReference<String> workerThread = new AtomicReference<>();
        RdsClient rds = mock(RdsClient.class);
        DescribeDBInstancesIterable it = mock(DescribeDBInstancesIterable.class);
        when(it.iterator()).thenReturn(Collections.emptyIterator());
        when(rds.describeDBInstancesPaginator(any(DescribeDbInstancesRequest.class))).thenReturn(it);
        when(awsClientProvider.getRdsClient(eq(account), anyString())).thenAnswer(inv -> {
            workerThread.set(Thread.currentThread().getName());
            return rds;
        });

        service.getDbInventory(AWS_ID, true).get();

        assertThat(workerThread.get()).startsWith("dbops-io-");
        assertThat(workerThread.get())
                .doesNotContain("ForkJoinPool")
                .doesNotContain("commonPool");
    }
}
