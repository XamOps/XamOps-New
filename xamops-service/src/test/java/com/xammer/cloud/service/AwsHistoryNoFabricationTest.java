package com.xammer.cloud.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.DailySpotSavings;
import com.xammer.cloud.dto.autospotting.CostResponse;
import com.xammer.cloud.dto.autospotting.HistoryResponse;
import com.xammer.cloud.repository.AsgSpotConfigRepository;
import com.xammer.cloud.repository.AutoSpottingSettingsRepository;
import com.xammer.cloud.repository.CachedDataRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.DailySpotSavingsRepository;
import com.xammer.cloud.repository.SpotOptimizationEventRepository;
import com.xammer.cloud.service.autospotting.DefaultAutoSpottingFlags;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Ledger §12.1: the AWS history no longer back-fills past days with today's live savings rate,
 * and potential is null for past days (nothing records it). The live rate is present in every
 * case below, so a back-fill would show up as invented points.
 */
class AwsHistoryNoFabricationTest {

    private static final String AWS_ID = "012345678901";
    private static final double LIVE_HOURLY_SAVINGS = 0.5;
    private static final double LIVE_HOURLY_POTENTIAL = 0.8;

    private DailySpotSavingsRepository savingsRepository;
    private SpotOptimizationEventRepository eventRepository;
    private RedisCacheService redisCacheService;
    private AutoSpottingService service;
    private CloudAccount account;

    @BeforeEach
    void setUp() {
        savingsRepository = mock(DailySpotSavingsRepository.class);
        eventRepository = mock(SpotOptimizationEventRepository.class);
        redisCacheService = mock(RedisCacheService.class);
        service = new AutoSpottingService(mock(AwsClientProvider.class), mock(CloudAccountRepository.class),
                mock(SpotPricingService.class), eventRepository, savingsRepository,
                mock(AutoSpottingSettingsRepository.class), mock(AsgSpotConfigRepository.class),
                redisCacheService, mock(CachedDataRepository.class), new ObjectMapper(),
                DefaultAutoSpottingFlags.load());

        account = new CloudAccount();
        account.setId(7L);
        account.setProvider("AWS");
        account.setAwsAccountId(AWS_ID);

        // Live cost data, served from the cache, so the account is "actively saving" right now.
        CostResponse.CostSummary summary = new CostResponse.CostSummary();
        summary.setTotalActualSavings(LIVE_HOURLY_SAVINGS);
        summary.setTotalPotentialSavings(LIVE_HOURLY_POTENTIAL);
        summary.setAutospottingEnabledCount(2);
        summary.setTotalCurrentHourlyCost(1.0);
        summary.setTotalOndemandHourlyCost(1.5);
        CostResponse live = new CostResponse();
        live.setSummary(summary);
        when(redisCacheService.get("autospotting-cost-" + AWS_ID, CostResponse.class)).thenReturn(Optional.of(live));
    }

    private static DailySpotSavings row(LocalDate date, double amount) {
        DailySpotSavings row = new DailySpotSavings(null, date);
        row.setAmountSaved(amount);
        row.setSwapCount(1);
        return row;
    }

    @Test
    void savedRows_haveNullPotential_andNoDayIsBackFilled() {
        LocalDate start = LocalDate.now().minusDays(10);
        LocalDate end = LocalDate.now().minusDays(3);
        when(savingsRepository.findByAccountIdAndDateBetweenOrderByDateAsc(7L, start, end))
                .thenReturn(List.of(row(start.plusDays(1), 4.0), row(start.plusDays(4), 6.0)));

        HistoryResponse history = service.getCostHistory(account, start.toString(), end.toString(), "daily");

        assertThat(history.getDataPoints()).extracting(HistoryResponse.DataPoint::getTimestamp)
                .containsExactly(start.plusDays(1).toString(), start.plusDays(4).toString());
        assertThat(history.getDataPoints()).allSatisfy(dp -> assertThat(dp.getPotentialHourlySavings()).isNull());
        assertThat(history.getDataPoints()).extracting(HistoryResponse.DataPoint::getActualHourlySavings)
                .containsExactly(4.0, 6.0);
        // The rows are legacy: reported apart, not in the total (Phase 02, AC-LED-11).
        assertThat(history.getSummary().getTotalActualSavings()).isZero();
        assertThat(history.getSummary().getLegacyTotalSavings()).isCloseTo(10.0, within(1e-9));
        assertThat(history.getSummary().getTotalPotentialSavings()).isZero();
    }

    @Test
    void emptyPastRange_returnsNoInventedPoints() {
        LocalDate start = LocalDate.now().minusDays(10);
        LocalDate end = LocalDate.now().minusDays(3);
        when(savingsRepository.findByAccountIdAndDateBetweenOrderByDateAsc(7L, start, end)).thenReturn(List.of());

        HistoryResponse history = service.getCostHistory(account, start.toString(), end.toString(), "daily");

        assertThat(history.getDataPoints()).isEmpty();
        assertThat(history.getSummary().getTotalActualSavings()).isZero();
        assertThat(history.getSummary().getTotalPotentialSavings()).isZero();
        assertThat(history.getInterval()).isEqualTo("daily"); // the real path ran, not the error fallback
        verify(eventRepository).findByAccountIdAndEventTimeBetweenAndStatusOrderByEventTimeDesc(
                eq(7L), any(), any(), eq("SUCCESS"));
    }

    @Test
    void rangeEndingToday_keepsOnlyTodaysLivePoint() {
        LocalDate today = LocalDate.now();
        LocalDate start = today.minusDays(7);
        when(savingsRepository.findByAccountIdAndDateBetweenOrderByDateAsc(7L, start, today)).thenReturn(List.of());

        HistoryResponse history = service.getCostHistory(account, start.toString(), today.toString(), "daily");

        // Today's point is the live rate as before; the seven past days are not invented from it.
        assertThat(history.getDataPoints()).hasSize(1);
        HistoryResponse.DataPoint todayPoint = history.getDataPoints().get(0);
        assertThat(todayPoint.getTimestamp()).isEqualTo(today.toString());
        assertThat(todayPoint.getActualHourlySavings()).isCloseTo(LIVE_HOURLY_SAVINGS * 24, within(1e-9));
        assertThat(todayPoint.getPotentialHourlySavings()).isCloseTo(LIVE_HOURLY_POTENTIAL * 24, within(1e-9));
    }
}
