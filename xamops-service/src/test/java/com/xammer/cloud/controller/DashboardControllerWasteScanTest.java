package com.xammer.cloud.controller;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.DashboardLayoutRepository;
import com.xammer.cloud.service.AwsAccountService;
import com.xammer.cloud.service.CloudListService;
import com.xammer.cloud.service.DashboardDataService;
import com.xammer.cloud.service.OptimizationService;
import com.xammer.cloud.service.WasteDeleteService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The waste endpoint's database lookups run off the request thread, so Open Session in View never
 * binds a connection to a request that then waits for a scan. A forced scan ("Scan Resources")
 * keeps the saved snapshot until it has a new result.
 */
class DashboardControllerWasteScanTest {

    private static final String AWS_ID = "333333333333";

    private DashboardDataService dashboardDataService;
    private OptimizationService optimizationService;
    private CloudListService cloudListService;
    private CloudAccountRepository cloudAccountRepository;
    private ThreadPoolTaskExecutor lookupExecutor;
    private DashboardController controller;

    @BeforeEach
    void setUp() {
        dashboardDataService = mock(DashboardDataService.class);
        optimizationService = mock(OptimizationService.class);
        cloudListService = mock(CloudListService.class);
        cloudAccountRepository = mock(CloudAccountRepository.class);

        lookupExecutor = new ThreadPoolTaskExecutor();
        lookupExecutor.setCorePoolSize(2);
        lookupExecutor.setMaxPoolSize(2);
        lookupExecutor.setQueueCapacity(10);
        lookupExecutor.setThreadNamePrefix("waste-lookup-");
        lookupExecutor.initialize();

        controller = new DashboardController(dashboardDataService, optimizationService, cloudListService,
                mock(AwsAccountService.class), cloudAccountRepository, mock(DashboardLayoutRepository.class),
                mock(WasteDeleteService.class), lookupExecutor);
    }

    @AfterEach
    void tearDown() {
        lookupExecutor.shutdown();
    }

    @Test
    void forcedScanKeepsThePreviousSnapshotUntilTheNewResultIsSaved() throws Exception {
        AtomicReference<String> lookupThread = new AtomicReference<>();
        CloudAccount account = new CloudAccount();
        account.setAwsAccountId(AWS_ID);
        when(cloudAccountRepository.findByAwsAccountId(AWS_ID)).thenAnswer(inv -> {
            lookupThread.set(Thread.currentThread().getName());
            return List.of(account);
        });
        List<DashboardData.RegionStatus> regions = List.of(new DashboardData.RegionStatus());
        when(cloudListService.getRegionStatusForAccount(account, true))
                .thenReturn(CompletableFuture.completedFuture(regions));
        List<DashboardData.WastedResource> found = List.of(new DashboardData.WastedResource());
        when(optimizationService.getWastedResources(account, regions, true))
                .thenReturn(CompletableFuture.completedFuture(found));

        List<DashboardData.WastedResource> result = controller.getWastedResources(AWS_ID, true)
                .get(5, TimeUnit.SECONDS);

        assertThat(result).isSameAs(found);
        verify(dashboardDataService, never()).deleteSnapshot(anyString());
        verify(dashboardDataService).saveWasteSnapshot("waste-" + AWS_ID, found);
        assertThat(lookupThread.get()).startsWith("waste-lookup-");
    }

    @Test
    void savedSnapshotIsServedWithoutQueryingOnTheRequestThread() throws Exception {
        AtomicReference<String> lookupThread = new AtomicReference<>();
        List<DashboardData.WastedResource> saved = List.of(new DashboardData.WastedResource());
        when(dashboardDataService.getWasteSnapshot("waste-" + AWS_ID)).thenAnswer(inv -> {
            lookupThread.set(Thread.currentThread().getName());
            return saved;
        });

        List<DashboardData.WastedResource> result = controller.getWastedResources(AWS_ID, false)
                .get(5, TimeUnit.SECONDS);

        assertThat(result).isSameAs(saved);
        assertThat(lookupThread.get()).startsWith("waste-lookup-");
        verifyNoInteractions(cloudAccountRepository, cloudListService, optimizationService);
    }

    @Test
    void unknownAccountStillFailsTheRequest() {
        when(cloudAccountRepository.findByAwsAccountId(AWS_ID)).thenReturn(List.of());

        CompletableFuture<List<DashboardData.WastedResource>> result = controller.getWastedResources(AWS_ID, true);

        assertThat(result).failsWithin(5, TimeUnit.SECONDS)
                .withThrowableThat()
                .havingRootCause()
                .withMessage("Account not found: " + AWS_ID);
        verifyNoInteractions(cloudListService, optimizationService);
    }
}
