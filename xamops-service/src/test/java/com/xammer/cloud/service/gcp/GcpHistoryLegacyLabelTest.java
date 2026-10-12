package com.xammer.cloud.service.gcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.DailySpotSavings;
import com.xammer.cloud.domain.SpotOptimizationEvent;
import com.xammer.cloud.dto.autospotting.CostResponse;
import com.xammer.cloud.dto.autospotting.HistoryResponse;
import com.xammer.cloud.repository.AsgSpotConfigRepository;
import com.xammer.cloud.repository.AutoSpottingSettingsRepository;
import com.xammer.cloud.repository.CachedDataRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.DailySpotSavingsRepository;
import com.xammer.cloud.repository.SpotOptimizationEventRepository;
import com.xammer.cloud.service.AutoSpottingService;
import com.xammer.cloud.service.AwsClientProvider;
import com.xammer.cloud.service.RedisCacheService;
import com.xammer.cloud.service.SpotPricingService;
import com.xammer.cloud.service.autospotting.DefaultAutoSpottingFlags;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The GCP and AWS history endpoints label every legacy point (D-12, FR-UI-05) and keep legacy
 * amounts out of the summary totals, reporting them apart (AC-LED-11). A point is legacy when its
 * amount comes from a {@code daily_spot_savings} row or from the {@code spot_optimization_event}
 * fallback; the AWS live point for today is not.
 *
 * <p>A day without data gets no point on either cloud, so no $0 is drawn for a day nothing was
 * recorded on (P01-R5, orchestrator decision A).
 */
class GcpHistoryLegacyLabelTest {

    private static final String PROJECT = "proj";
    private static final String AWS_ID = "012345678901";
    private static final double LIVE_HOURLY_SAVINGS = 0.5;

    private DailySpotSavingsRepository savingsRepository;
    private SpotOptimizationEventRepository eventRepository;
    private GcpAutoSpottingService gcpService;
    private AutoSpottingService awsService;
    private CloudAccount awsAccount;

    @BeforeEach
    void setUp() {
        savingsRepository = mock(DailySpotSavingsRepository.class);
        eventRepository = mock(SpotOptimizationEventRepository.class);

        CloudAccountRepository cloudAccountRepository = mock(CloudAccountRepository.class);
        gcpService = new GcpAutoSpottingService(mock(GcpClientProvider.class), cloudAccountRepository, eventRepository,
                savingsRepository, mock(GcpPricingService.class), mock(GcpRestTemplateService.class),
                mock(RedisCacheService.class), DefaultAutoSpottingFlags.load());
        CloudAccount gcpAccount = new CloudAccount();
        gcpAccount.setId(3L);
        gcpAccount.setProvider("GCP");
        gcpAccount.setGcpProjectId(PROJECT);
        when(cloudAccountRepository.findByProviderAccountId(PROJECT)).thenReturn(Optional.of(gcpAccount));

        RedisCacheService awsCache = mock(RedisCacheService.class);
        awsService = new AutoSpottingService(mock(AwsClientProvider.class), mock(CloudAccountRepository.class),
                mock(SpotPricingService.class), eventRepository, savingsRepository,
                mock(AutoSpottingSettingsRepository.class), mock(AsgSpotConfigRepository.class),
                awsCache, mock(CachedDataRepository.class), new ObjectMapper(),
                DefaultAutoSpottingFlags.load());
        awsAccount = new CloudAccount();
        awsAccount.setId(7L);
        awsAccount.setProvider("AWS");
        awsAccount.setAwsAccountId(AWS_ID);
        // Live cost data, served from the cache: the source of today's AWS point.
        CostResponse.CostSummary summary = new CostResponse.CostSummary();
        summary.setTotalActualSavings(LIVE_HOURLY_SAVINGS);
        summary.setTotalPotentialSavings(0.8);
        summary.setAutospottingEnabledCount(1);
        summary.setTotalCurrentHourlyCost(1.0);
        summary.setTotalOndemandHourlyCost(1.5);
        CostResponse live = new CostResponse();
        live.setSummary(summary);
        when(awsCache.get("autospotting-cost-" + AWS_ID, CostResponse.class)).thenReturn(Optional.of(live));
    }

    private static DailySpotSavings row(LocalDate date, double amount) {
        DailySpotSavings row = new DailySpotSavings(null, date);
        row.setAmountSaved(amount);
        row.setSwapCount(1);
        return row;
    }

    private static SpotOptimizationEvent event(LocalDate day, double hourlySavings, String status) {
        SpotOptimizationEvent event = new SpotOptimizationEvent();
        event.setEventTime(day.atTime(10, 0).toInstant(ZoneOffset.UTC));
        event.setHourlySavings(hourlySavings);
        event.setStatus(status);
        return event;
    }

    // ── GCP ─────────────────────────────────────────────────────────────────

    @Test
    void gcpRows_areLabelledLegacy_andLeftOutOfEverySummaryTotal() {
        LocalDate today = LocalDate.now();
        when(savingsRepository.findByAccountIdAndDateBetweenOrderByDateAsc(3L, today.minusDays(7), today))
                .thenReturn(List.of(row(today.minusDays(5), 4.0), row(today.minusDays(2), 6.0)));

        HistoryResponse history = gcpService.getHistoryData(PROJECT, "last7d");

        assertThat(history.getDataPoints()).hasSize(2)
                .allSatisfy(dp -> assertThat(dp.getLegacy()).isTrue());
        HistoryResponse.HistorySummary summary = history.getSummary();
        assertThat(summary.getTotalActualSavings()).isZero();
        assertThat(summary.getAverageHourlySavings()).isZero();
        assertThat(summary.getPeakSavings()).isZero();
        assertThat(summary.getLegacyTotalSavings()).isCloseTo(10.0, within(1e-9));
    }

    @Test
    void gcpMissingDays_getNoPoint_soNoZeroIsDrawnForThem() {
        LocalDate today = LocalDate.now();
        LocalDate first = today.minusDays(6);
        LocalDate second = today.minusDays(1);
        // Two days with data in an eight-day range.
        when(savingsRepository.findByAccountIdAndDateBetweenOrderByDateAsc(3L, today.minusDays(7), today))
                .thenReturn(List.of(row(first, 3.0), row(second, 2.0)));

        HistoryResponse history = gcpService.getHistoryData(PROJECT, "last7d");

        assertThat(history.getDataPoints()).extracting(HistoryResponse.DataPoint::getTimestamp)
                .containsExactly(first.toString(), second.toString());
        assertThat(history.getDataPoints()).extracting(HistoryResponse.DataPoint::getActualHourlySavings)
                .containsExactly(3.0, 2.0);
        assertThat(history.getDataPoints()).allSatisfy(dp -> assertThat(dp.getPotentialHourlySavings()).isNull());
    }

    @Test
    void gcpRangeWithNoDataAtAll_returnsNoPoints() {
        LocalDate today = LocalDate.now();
        when(savingsRepository.findByAccountIdAndDateBetweenOrderByDateAsc(3L, today.minusDays(30), today))
                .thenReturn(List.of());
        when(eventRepository.findByAccountIdAndEventTimeBetweenOrderByEventTimeDesc(eq(3L), any(), any()))
                .thenReturn(List.of());

        HistoryResponse history = gcpService.getHistoryData(PROJECT, "last30d");

        // Was 31 zero points; now nothing, so the chart shows its "no data" message.
        assertThat(history.getDataPoints()).isEmpty();
        assertThat(history.getSummary().getTotalActualSavings()).isZero();
        assertThat(history.getSummary().getAverageHourlySavings()).isZero();
        assertThat(history.getSummary().getPeakSavings()).isZero();
        assertThat(history.getSummary().getLegacyTotalSavings()).isZero();
    }

    @Test
    void gcpEventFallback_isLabelledLegacy() {
        LocalDate today = LocalDate.now();
        LocalDate day = today.minusDays(1);
        when(savingsRepository.findByAccountIdAndDateBetweenOrderByDateAsc(3L, today.minusDays(7), today))
                .thenReturn(List.of());
        when(eventRepository.findByAccountIdAndEventTimeBetweenOrderByEventTimeDesc(eq(3L), any(), any()))
                .thenReturn(List.of(event(day, 0.3, "ENABLED"), event(day, 0.2, "SUCCESS")));

        HistoryResponse history = gcpService.getHistoryData(PROJECT, "last7d");

        assertThat(history.getDataPoints()).hasSize(1);
        HistoryResponse.DataPoint point = history.getDataPoints().get(0);
        assertThat(point.getTimestamp()).isEqualTo(day.toString());
        assertThat(point.getLegacy()).isTrue();
        assertThat(history.getSummary().getTotalActualSavings()).isZero();
        assertThat(history.getSummary().getLegacyTotalSavings()).isCloseTo(0.5, within(1e-9));
    }

    // ── AWS ─────────────────────────────────────────────────────────────────

    @Test
    void awsRows_areLegacy_todaysLivePointIsNot_andOnlyItReachesTheTotals() {
        LocalDate today = LocalDate.now();
        LocalDate start = today.minusDays(7);
        when(savingsRepository.findByAccountIdAndDateBetweenOrderByDateAsc(7L, start, today))
                .thenReturn(List.of(row(today.minusDays(4), 4.0), row(today.minusDays(2), 6.0)));

        HistoryResponse history = awsService.getCostHistory(awsAccount, start.toString(), today.toString(), "daily");

        assertThat(history.getDataPoints()).extracting(HistoryResponse.DataPoint::getLegacy)
                .containsExactly(true, true, false);
        HistoryResponse.DataPoint todayPoint = history.getDataPoints().get(2);
        assertThat(todayPoint.getTimestamp()).isEqualTo(today.toString());
        double liveDaily = LIVE_HOURLY_SAVINGS * 24;
        HistoryResponse.HistorySummary summary = history.getSummary();
        assertThat(summary.getTotalActualSavings()).isCloseTo(liveDaily, within(1e-9));
        assertThat(summary.getAverageHourlySavings()).isCloseTo(LIVE_HOURLY_SAVINGS, within(1e-9));
        assertThat(summary.getPeakSavings()).isCloseTo(LIVE_HOURLY_SAVINGS, within(1e-9));
        assertThat(summary.getLegacyTotalSavings()).isCloseTo(10.0, within(1e-9));
    }

    @Test
    void awsEventFallback_isLabelledLegacy() {
        LocalDate start = LocalDate.now().minusDays(10);
        LocalDate end = LocalDate.now().minusDays(3);
        LocalDate day = start.plusDays(2);
        when(savingsRepository.findByAccountIdAndDateBetweenOrderByDateAsc(7L, start, end)).thenReturn(List.of());
        when(eventRepository.findByAccountIdAndEventTimeBetweenAndStatusOrderByEventTimeDesc(
                eq(7L), any(), any(), eq("SUCCESS")))
                .thenReturn(List.of(event(day, 0.4, "SUCCESS"), event(day, 0.1, "SUCCESS")));

        HistoryResponse history = awsService.getCostHistory(awsAccount, start.toString(), end.toString(), "daily");

        assertThat(history.getDataPoints()).hasSize(1);
        assertThat(history.getDataPoints().get(0).getLegacy()).isTrue();
        assertThat(history.getSummary().getTotalActualSavings()).isZero();
        assertThat(history.getSummary().getLegacyTotalSavings()).isCloseTo(0.5, within(1e-9));
    }
}
