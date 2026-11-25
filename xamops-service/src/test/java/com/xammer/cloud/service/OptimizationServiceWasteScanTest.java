package com.xammer.cloud.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.repository.CachedDataRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Address;
import software.amazon.awssdk.services.ec2.model.DescribeAddressesResponse;
import software.amazon.awssdk.services.rds.RdsClient;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The waste scan's containment: its AWS checks run on their own pool, a second scan for the same
 * account waits for the one already running, and a scan answers with what it has once its time
 * limit passes. The AWS clients are mocks; detectors whose client is not stubbed fail fast and
 * contribute nothing, which is what the scan already does for a failed check.
 */
class OptimizationServiceWasteScanTest {

    private static final String AWS_ID = "222222222222";

    private AwsClientProvider awsClientProvider;
    private ThreadPoolTaskExecutor executor;
    private OptimizationService service;
    private CloudAccount account;
    private List<DashboardData.RegionStatus> regions;
    /** Holds blocked AWS calls until the test lets them go; always released in tearDown. */
    private final CountDownLatch release = new CountDownLatch(1);

    @BeforeEach
    void setUp() {
        awsClientProvider = mock(AwsClientProvider.class);

        // A real bounded pool with the production thread prefix.
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("waste-scan-");
        executor.initialize();

        service = new OptimizationService(mock(CloudAccountRepository.class), awsClientProvider,
                mock(PricingService.class), mock(CloudListService.class), mock(RedisCacheService.class),
                mock(CachedDataRepository.class), new ObjectMapper(), mock(SnapshotFreshnessService.class),
                executor);

        account = new CloudAccount();
        account.setId(1L);
        account.setAwsAccountId(AWS_ID);
        account.setProvider("AWS");
        DashboardData.RegionStatus region = new DashboardData.RegionStatus();
        region.setRegionId("us-east-1");
        regions = List.of(region);
    }

    @AfterEach
    void tearDown() {
        release.countDown();
        executor.shutdown();
    }

    @Test
    void regionalChecksRunOnTheWasteScanPool() throws Exception {
        AtomicReference<String> workerThread = new AtomicReference<>();
        when(awsClientProvider.getEc2Client(eq(account), anyString())).thenAnswer(inv -> {
            workerThread.compareAndSet(null, Thread.currentThread().getName());
            return mock(Ec2Client.class);
        });

        service.getWastedResources(account, regions, true).get(30, TimeUnit.SECONDS);

        assertThat(workerThread.get())
                .startsWith("waste-scan-")
                .doesNotContain("ForkJoinPool");
    }

    @Test
    void secondScanForTheSameAccountWaitsForTheFirst() throws Exception {
        CountDownLatch rdsCalled = new CountDownLatch(1);
        when(awsClientProvider.getRdsClient(eq(account), anyString())).thenAnswer(inv -> {
            rdsCalled.countDown();
            release.await(30, TimeUnit.SECONDS);
            return mock(RdsClient.class);
        });

        CompletableFuture<List<DashboardData.WastedResource>> first = service.getWastedResources(account, regions, true);
        assertThat(rdsCalled.await(10, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<List<DashboardData.WastedResource>> second = service.getWastedResources(account, regions, true);

        assertThat(second).isSameAs(first);
        release.countDown();
        first.get(30, TimeUnit.SECONDS);
        // Two RDS detectors, one region: two calls for one scan, four if the second request had
        // started its own.
        verify(awsClientProvider, times(2)).getRdsClient(eq(account), anyString());

        // Once the scan has finished, the next request starts a fresh one.
        service.getWastedResources(account, regions, true).get(30, TimeUnit.SECONDS);
        verify(awsClientProvider, times(4)).getRdsClient(eq(account), anyString());
    }

    @Test
    void scanReturnsWhatFinishedWhenTheTimeLimitPasses() throws Exception {
        ReflectionTestUtils.setField(service, "wasteScanTimeoutSeconds", 3L);
        Ec2Client ec2 = mock(Ec2Client.class);
        when(ec2.describeAddresses()).thenReturn(DescribeAddressesResponse.builder()
                .addresses(Address.builder().allocationId("eipalloc-1").publicIp("203.0.113.10").build())
                .build());
        when(awsClientProvider.getEc2Client(eq(account), anyString())).thenReturn(ec2);
        // The RDS checks hang for the whole test, like an AWS call that never answers.
        when(awsClientProvider.getRdsClient(eq(account), anyString())).thenAnswer(inv -> {
            release.await(60, TimeUnit.SECONDS);
            return mock(RdsClient.class);
        });

        long start = System.nanoTime();
        List<DashboardData.WastedResource> result = service.getWastedResources(account, regions, true)
                .get(20, TimeUnit.SECONDS);
        long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(tookMillis).isLessThan(10_000);
        assertThat(result).extracting(DashboardData.WastedResource::getResourceId).contains("eipalloc-1");
    }
}
