package com.xammer.cloud.service.gcp;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.DailySpotSavings;
import com.xammer.cloud.domain.SpotOptimizationEvent;
import com.xammer.cloud.dto.autospotting.HistoryResponse;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.DailySpotSavingsRepository;
import com.xammer.cloud.repository.SpotOptimizationEventRepository;
import com.xammer.cloud.service.RedisCacheService;
import com.xammer.cloud.service.autospotting.DefaultAutoSpottingFlags;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Ledger §12.1: the GCP history no longer invents "potential = 20% of actual". Potential is null
 * on every point built from a stored row or event, and the JSON field stays. Since Phase 02 a day
 * without data gets no point at all (P01-R5), and legacy amounts are summed apart from the totals
 * ({@link GcpHistoryLegacyLabelTest}).
 */
class GcpHistoryNoFabricationTest {

    private static final String PROJECT = "proj";

    private DailySpotSavingsRepository savingsRepository;
    private SpotOptimizationEventRepository eventRepository;
    private GcpAutoSpottingService service;
    private CloudAccount account;

    @BeforeEach
    void setUp() {
        CloudAccountRepository cloudAccountRepository = mock(CloudAccountRepository.class);
        savingsRepository = mock(DailySpotSavingsRepository.class);
        eventRepository = mock(SpotOptimizationEventRepository.class);
        service = new GcpAutoSpottingService(mock(GcpClientProvider.class), cloudAccountRepository, eventRepository,
                savingsRepository, mock(GcpPricingService.class), mock(GcpRestTemplateService.class),
                mock(RedisCacheService.class), DefaultAutoSpottingFlags.load());

        account = new CloudAccount();
        account.setId(3L);
        account.setProvider("GCP");
        account.setGcpProjectId(PROJECT);
        when(cloudAccountRepository.findByProviderAccountId(PROJECT)).thenReturn(Optional.of(account));
    }

    private static DailySpotSavings row(LocalDate date, double amount) {
        DailySpotSavings row = new DailySpotSavings(null, date);
        row.setAmountSaved(amount);
        return row;
    }

    private static SpotOptimizationEvent event(LocalDate day, double hourlySavings) {
        SpotOptimizationEvent event = new SpotOptimizationEvent();
        event.setEventTime(day.atTime(10, 0).toInstant(ZoneOffset.UTC));
        event.setHourlySavings(hourlySavings);
        event.setStatus("ENABLED");
        return event;
    }

    private static boolean nullOrZero(Double value) {
        return value == null || value == 0.0;
    }

    private static Map<String, HistoryResponse.DataPoint> byDay(HistoryResponse history) {
        return history.getDataPoints().stream()
                .collect(Collectors.toMap(HistoryResponse.DataPoint::getTimestamp, Function.identity()));
    }

    @Test
    void savedRows_haveNullPotential() {
        LocalDate today = LocalDate.now();
        LocalDate first = today.minusDays(5);
        LocalDate second = today.minusDays(2);
        when(savingsRepository.findByAccountIdAndDateBetweenOrderByDateAsc(3L, today.minusDays(7), today))
                .thenReturn(List.of(row(first, 4.0), row(second, 6.0)));

        HistoryResponse history = service.getHistoryData(PROJECT, "last7d");

        Map<String, HistoryResponse.DataPoint> points = byDay(history);
        assertThat(points.get(first.toString()).getActualHourlySavings()).isEqualTo(4.0);
        assertThat(points.get(first.toString()).getPotentialHourlySavings()).isNull();
        assertThat(points.get(second.toString()).getActualHourlySavings()).isEqualTo(6.0);
        assertThat(points.get(second.toString()).getPotentialHourlySavings()).isNull();
        // No point carries an invented potential.
        assertThat(history.getDataPoints()).allSatisfy(dp -> assertThat(nullOrZero(dp.getPotentialHourlySavings()))
                .as("potential on %s", dp.getTimestamp()).isTrue());
        // The rows are legacy: reported apart, not in the total (Phase 02, AC-LED-11).
        assertThat(history.getSummary().getTotalActualSavings()).isZero();
        assertThat(history.getSummary().getLegacyTotalSavings()).isCloseTo(10.0, within(1e-9));
    }

    @Test
    void eventFallback_hasNullPotential() {
        LocalDate today = LocalDate.now();
        LocalDate day = today.minusDays(1);
        when(savingsRepository.findByAccountIdAndDateBetweenOrderByDateAsc(3L, today.minusDays(7), today))
                .thenReturn(List.of());
        when(eventRepository.findByAccountIdAndEventTimeBetweenOrderByEventTimeDesc(eq(3L), any(), any()))
                .thenReturn(List.of(event(day, 0.3), event(day, 0.2)));

        HistoryResponse history = service.getHistoryData(PROJECT, "last7d");

        HistoryResponse.DataPoint point = byDay(history).get(day.toString());
        assertThat(point.getActualHourlySavings()).isCloseTo(0.5, within(1e-9));
        assertThat(point.getPotentialHourlySavings()).isNull();
    }

    @Test
    void emptyRange_inventsNoSavings() {
        LocalDate today = LocalDate.now();
        when(savingsRepository.findByAccountIdAndDateBetweenOrderByDateAsc(3L, today.minusDays(7), today))
                .thenReturn(List.of());

        HistoryResponse history = service.getHistoryData(PROJECT, "last7d");

        assertThat(history.getDataPoints()).allSatisfy(dp -> {
            assertThat(dp.getActualHourlySavings()).isZero();
            assertThat(nullOrZero(dp.getPotentialHourlySavings())).as("potential on %s", dp.getTimestamp()).isTrue();
        });
        assertThat(history.getSummary().getTotalActualSavings()).isZero();
        assertThat(history.getSummary().getPeakSavings()).isZero();
    }
}
