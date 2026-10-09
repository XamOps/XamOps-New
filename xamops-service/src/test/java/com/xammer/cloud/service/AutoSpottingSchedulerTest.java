package com.xammer.cloud.service;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.DailySpotSavings;
import com.xammer.cloud.dto.autospotting.ASGCostData;
import com.xammer.cloud.dto.autospotting.CostResponse;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.DailySpotSavingsRepository;
import com.xammer.cloud.service.autospotting.AutoSpottingFeatureFlags;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The nightly AWS ledger writer must not touch daily_spot_savings while
 * autospotting.aws.savings-ledger.enabled is false, and must pass the CloudAccount (not a
 * primary key) to the service when it is on.
 */
class AutoSpottingSchedulerTest {

    private CloudAccountRepository cloudAccountRepository;
    private DailySpotSavingsRepository savingsRepository;
    private AutoSpottingService autoSpottingService;

    @BeforeEach
    void setUp() {
        cloudAccountRepository = mock(CloudAccountRepository.class);
        savingsRepository = mock(DailySpotSavingsRepository.class);
        autoSpottingService = mock(AutoSpottingService.class);
    }

    private AutoSpottingScheduler scheduler(boolean ledgerEnabled) {
        return new AutoSpottingScheduler(cloudAccountRepository, savingsRepository, autoSpottingService,
                new AutoSpottingFeatureFlags(false, false, ledgerEnabled));
    }

    @Test
    void ledgerOff_recordDailySavingsTouchesNothing() {
        scheduler(false).recordDailySavings();

        verifyNoInteractions(cloudAccountRepository, savingsRepository, autoSpottingService);
    }

    @Test
    void ledgerOff_backfillTouchesNothing() {
        scheduler(false).backfillHistoricalSavings(7L, LocalDate.now().minusDays(3), LocalDate.now());

        verifyNoInteractions(cloudAccountRepository, savingsRepository, autoSpottingService);
    }

    @Test
    void ledgerOn_passesTheCloudAccountAndSavesOneRow() {
        CloudAccount account = new CloudAccount();
        account.setId(7L);
        account.setProvider("AWS");
        account.setAwsAccountId("012345678901");
        account.setAccountName("prod");

        ASGCostData asg = new ASGCostData();
        asg.setAutospottingEnabled(true);
        CostResponse.CostSummary summary = new CostResponse.CostSummary();
        summary.setTotalActualSavings(0.5);
        CostResponse cost = new CostResponse();
        cost.setSummary(summary);
        cost.setAsgs(List.of(asg));

        when(cloudAccountRepository.findAll()).thenReturn(List.of(account));
        when(autoSpottingService.getCostData(account)).thenReturn(cost);
        when(savingsRepository.findByAccountIdAndDate(any(), any())).thenReturn(Optional.empty());

        scheduler(true).recordDailySavings();

        verify(autoSpottingService).getCostData(account);
        ArgumentCaptor<DailySpotSavings> saved = ArgumentCaptor.forClass(DailySpotSavings.class);
        verify(savingsRepository).save(saved.capture());
        assertThat(saved.getValue().getAmountSaved()).isCloseTo(12.0, within(1e-9));
        assertThat(saved.getValue().getSwapCount()).isEqualTo(1);
    }
}
