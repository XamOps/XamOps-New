package com.xammer.cloud.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.config.DbOpsFeatureFlags;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.dbops.DbSavingsSummaryDto;
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
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricDataRequest;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricDataResponse;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeRegionsResponse;
import software.amazon.awssdk.services.ec2.model.Region;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.DBInstance;
import software.amazon.awssdk.services.rds.model.DescribeDbInstancesRequest;
import software.amazon.awssdk.services.rds.model.DescribeDbInstancesResponse;
import software.amazon.awssdk.services.rds.paginators.DescribeDBInstancesIterable;

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
 * PERF-3: a forced Refresh collapses to a SINGLE refresh path. With idle + rightsizing enabled and
 * forceRefresh propagated uniformly, the per-key single-flight means the whole summary drives
 * exactly one inventory scan and one CloudWatch GetMetricData fetch, and the returned instance count
 * reflects the fresh scan.
 */
class DbOpsCostServiceRefreshTest {

    private static final String AWS_ID = "111111111111";

    private CloudAccountRepository cloudAccountRepository;
    private AwsClientProvider awsClientProvider;
    private RedisCacheService redisCache;
    private DbOpsFeatureFlags featureFlags;
    private ExecutorService pool;
    private DbOpsCostService service;
    private CloudAccount account;

    @BeforeEach
    void setUp() {
        cloudAccountRepository = mock(CloudAccountRepository.class);
        awsClientProvider = mock(AwsClientProvider.class);
        redisCache = mock(RedisCacheService.class);
        featureFlags = mock(DbOpsFeatureFlags.class);
        when(featureFlags.isIdleEnabled()).thenReturn(true);
        when(featureFlags.isRightsizingEnabled()).thenReturn(true);
        account = new CloudAccount();
        account.setId(1L);
        account.setAwsAccountId(AWS_ID);
        account.setProvider("AWS");
        when(cloudAccountRepository.findByAwsAccountId(AWS_ID)).thenReturn(List.of(account));

        pool = Executors.newFixedThreadPool(8);

        service = new DbOpsCostService(cloudAccountRepository, awsClientProvider, redisCache,
                mock(CachedDataRepository.class), new ObjectMapper(), mock(AgentDataCacheRepository.class),
                mock(KubernetesClusterConfigRepository.class), mock(DbAgentConfigRepository.class),
                mock(DbAgentDataCacheRepository.class), mock(DbAgentMetricsHistoryRepository.class),
                mock(SpotPricingService.class), featureFlags, pool);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    private DBInstance db(String id) {
        return DBInstance.builder().dbInstanceIdentifier(id).dbInstanceClass("db.t3.micro").engine("mysql").build();
    }

    @Test
    void forcedSummaryRunsOneInventoryScanAndOneMetricsFetch() throws Exception {
        Ec2Client ec2 = mock(Ec2Client.class);
        when(awsClientProvider.getEc2Client(eq(account), anyString())).thenReturn(ec2);
        when(ec2.describeRegions()).thenReturn(DescribeRegionsResponse.builder()
                .regions(Region.builder().regionName("us-east-1").optInStatus("opt-in-not-required").build())
                .build());

        AtomicInteger inventoryScans = new AtomicInteger(0);
        AtomicInteger metricCalls = new AtomicInteger(0);
        CountDownLatch release = new CountDownLatch(1);

        DescribeDbInstancesResponse page = DescribeDbInstancesResponse.builder()
                .dbInstances(db("db1"), db("db2"), db("db3"))
                .build();
        RdsClient rds = mock(RdsClient.class);
        DescribeDBInstancesIterable it = mock(DescribeDBInstancesIterable.class);
        when(it.iterator()).thenAnswer(inv -> List.of(page).iterator());
        // Block the inventory scan so it is still in flight while getSavingsSummary wires up the
        // idle + rightsizing futures, letting their getDbMetrics calls join the one in-flight scan.
        when(rds.describeDBInstancesPaginator(any(DescribeDbInstancesRequest.class))).thenAnswer(inv -> {
            inventoryScans.incrementAndGet();
            release.await();
            return it;
        });
        when(awsClientProvider.getRdsClient(eq(account), anyString())).thenReturn(rds);

        CloudWatchClient cw = mock(CloudWatchClient.class);
        when(awsClientProvider.getCloudWatchClient(eq(account), eq("us-east-1"))).thenReturn(cw);
        when(cw.getMetricData(any(GetMetricDataRequest.class))).thenAnswer(inv -> {
            metricCalls.incrementAndGet();
            return GetMetricDataResponse.builder().build();
        });

        // getSavingsSummary returns after wiring all futures (inventory is blocked); release then.
        CompletableFuture<DbSavingsSummaryDto> summaryFuture = service.getSavingsSummary(AWS_ID, true);
        release.countDown();
        DbSavingsSummaryDto summary = summaryFuture.get(10, TimeUnit.SECONDS);

        assertThat(inventoryScans.get()).as("one inventory scan served the whole refresh").isEqualTo(1);
        assertThat(metricCalls.get()).as("one GetMetricData fetch served the whole refresh").isEqualTo(1);
        assertThat(summary.getTotalInstances()).as("count reflects the fresh scan").isEqualTo(3);
    }
}
