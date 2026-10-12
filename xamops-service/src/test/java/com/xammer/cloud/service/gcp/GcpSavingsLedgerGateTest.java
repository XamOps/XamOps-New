package com.xammer.cloud.service.gcp;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.DailySpotSavingsRepository;
import com.xammer.cloud.repository.SpotOptimizationEventRepository;
import com.xammer.cloud.service.RedisCacheService;
import com.xammer.cloud.service.autospotting.AutoSpottingFeatureFlags;
import com.xammer.cloud.service.autospotting.DefaultAutoSpottingFlags;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * FR-LED-29 / AC-LED-19: with no {@code autospotting.gcp.savings-ledger.enabled} line, none of the
 * four GCP legacy writers touches {@code daily_spot_savings}. Each method returns at its first
 * statement, before any repository, GCP client or REST call. Nothing here reaches GCP.
 */
class GcpSavingsLedgerGateTest {

    private static final String PROJECT = "proj";

    private CloudAccountRepository cloudAccountRepository;
    private DailySpotSavingsRepository savingsRepository;
    private SpotOptimizationEventRepository eventRepository;
    private GcpClientProvider gcpClientProvider;
    private GcpRestTemplateService restTemplateService;
    private GcpPricingService pricingService;
    private RedisCacheService redisCacheService;
    private AutoSpottingFeatureFlags flags;
    private GcpAutoSpottingService service;
    private CloudAccount account;

    @BeforeEach
    void setUp() {
        cloudAccountRepository = mock(CloudAccountRepository.class);
        savingsRepository = mock(DailySpotSavingsRepository.class);
        eventRepository = mock(SpotOptimizationEventRepository.class);
        gcpClientProvider = mock(GcpClientProvider.class);
        restTemplateService = mock(GcpRestTemplateService.class);
        pricingService = mock(GcpPricingService.class);
        redisCacheService = mock(RedisCacheService.class);
        flags = DefaultAutoSpottingFlags.load();
        service = new GcpAutoSpottingService(gcpClientProvider, cloudAccountRepository, eventRepository,
                savingsRepository, pricingService, restTemplateService, redisCacheService, flags);

        account = new CloudAccount();
        account.setId(3L);
        account.setProvider("GCP");
        account.setAccountName("sandbox");
        account.setGcpProjectId(PROJECT);
        // Data the writers would act on if they were not gated.
        when(cloudAccountRepository.findAll()).thenReturn(List.of(account));
        when(cloudAccountRepository.findByProvider("GCP")).thenReturn(List.of(account));
        when(cloudAccountRepository.findByProviderAccountId(PROJECT)).thenReturn(Optional.of(account));
    }

    @Test
    void defaultConfig_gcpLedgerIsOff() {
        assertThat(flags.isGcpSavingsLedgerEnabled()).isFalse();
    }

    @Test
    void serviceRecordDailySavingsForAllAccounts_writesNothing() {
        service.recordDailySavingsForAllAccounts();

        verifyNoInteractions(savingsRepository, cloudAccountRepository, gcpClientProvider,
                restTemplateService, pricingService, redisCacheService);
    }

    @Test
    void serviceUpdateDailySavings_writesNothing() {
        // Private callee of the enable path. The gate is inside it, so enableAutoSpotting is unchanged.
        ReflectionTestUtils.invokeMethod(service, "updateDailySavings", account, 0.42);

        verifyNoInteractions(savingsRepository);
    }

    @Test
    void schedulerRecordDailySavings_writesNothing() {
        GcpAutoSpottingService gcpService = mock(GcpAutoSpottingService.class);
        GcpAutoSpottingScheduler scheduler = new GcpAutoSpottingScheduler(
                cloudAccountRepository, savingsRepository, gcpService, flags);

        scheduler.recordDailySavings();

        verifyNoInteractions(savingsRepository, cloudAccountRepository, gcpService);
    }

    @Test
    void schedulerBackfillHistoricalSavings_writesNothing() {
        GcpAutoSpottingService gcpService = mock(GcpAutoSpottingService.class);
        when(gcpService.resolveAccount(PROJECT)).thenReturn(account);
        GcpAutoSpottingScheduler scheduler = new GcpAutoSpottingScheduler(
                cloudAccountRepository, savingsRepository, gcpService, flags);

        scheduler.backfillHistoricalSavings(PROJECT, LocalDate.now().minusDays(3), LocalDate.now());

        verifyNoInteractions(savingsRepository, cloudAccountRepository, gcpService);
    }
}
