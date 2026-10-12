package com.xammer.cloud.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.AutoSpottingSettings;
import com.xammer.cloud.domain.Client;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.DailySpotSavings;
import com.xammer.cloud.domain.SpotOptimizationEvent;
import com.xammer.cloud.repository.AsgSpotConfigRepository;
import com.xammer.cloud.repository.AutoSpottingSettingsRepository;
import com.xammer.cloud.repository.CachedDataRepository;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.repository.DailySpotSavingsRepository;
import com.xammer.cloud.repository.SpotOptimizationEventRepository;
import com.xammer.cloud.service.autospotting.DefaultAutoSpottingFlags;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The admin dashboard reads (FR-LED-27, AC-LED-11, AWS-13): every realized figure comes from
 * non-legacy rows only, legacy amounts are returned apart, and the dummy figures are gone.
 *
 * <p>Every production row is legacy, so the realized reads return nothing here, as they do in
 * production until the ledger exists. Where a test needs a non-legacy figure, it is a test-only
 * stub of the realized query.
 */
class AutoSpottingAdminMetricsTest {

    private static final LocalDate ALL_TIME_START = LocalDate.of(2000, 1, 1);

    private CloudAccountRepository cloudAccountRepository;
    private SpotOptimizationEventRepository eventRepository;
    private DailySpotSavingsRepository savingsRepository;
    private AutoSpottingSettingsRepository settingsRepository;
    private RedisCacheService redisCacheService;
    private AutoSpottingService service;

    @BeforeEach
    void setUp() {
        cloudAccountRepository = mock(CloudAccountRepository.class);
        eventRepository = mock(SpotOptimizationEventRepository.class);
        savingsRepository = mock(DailySpotSavingsRepository.class);
        settingsRepository = mock(AutoSpottingSettingsRepository.class);
        redisCacheService = mock(RedisCacheService.class);
        service = new AutoSpottingService(mock(AwsClientProvider.class), cloudAccountRepository,
                mock(SpotPricingService.class), eventRepository, savingsRepository,
                settingsRepository, mock(AsgSpotConfigRepository.class),
                redisCacheService, mock(CachedDataRepository.class), new ObjectMapper(),
                DefaultAutoSpottingFlags.load());
    }

    private static SpotOptimizationEvent successEvent(double hourlySavings) {
        SpotOptimizationEvent event = new SpotOptimizationEvent();
        event.setEventTime(Instant.now());
        event.setHourlySavings(hourlySavings);
        event.setStatus("SUCCESS");
        return event;
    }

    private void todaysEvents(SpotOptimizationEvent... events) {
        when(eventRepository.findByEventTimeBetweenAndStatusOrderByEventTimeDesc(any(), any(), eq("SUCCESS")))
                .thenReturn(List.of(events));
    }

    private static CloudAccount account(long id, String provider, Client client) {
        CloudAccount account = new CloudAccount();
        account.setId(id);
        account.setProvider(provider);
        account.setAccountName("account-" + id);
        if ("AWS".equals(provider)) account.setAwsAccountId(String.format("%012d", id));
        account.setClient(client);
        return account;
    }

    // ── /total-savings ──────────────────────────────────────────────────────

    @Test
    void dashboardMetrics_totalExcludesLegacy_legacyReportedApart_noDummyKeys() {
        when(savingsRepository.getTotalSavings()).thenReturn(null);      // no non-legacy row
        when(savingsRepository.getLegacyTotalSavings()).thenReturn(1500.0);
        todaysEvents(successEvent(0.5), successEvent(0.25));

        Map<String, Object> metrics = service.getAdminDashboardMetrics();

        assertThat(metrics.get("totalSavings")).isEqualTo(0.0);
        // Legacy rows plus today's event figure, which the total used to include.
        assertThat((Double) metrics.get("legacyUnverifiedTotal")).isCloseTo(1500.75, within(1e-9));
        assertThat(metrics.get("activeSpotInstances")).isEqualTo(2);
        assertThat(metrics).doesNotContainKeys("conversionRate", "monthlySavings", "dailySavings");
    }

    @Test
    void dashboardMetrics_realizedTotalReadsOnlyTheNonLegacyQuery() {
        when(savingsRepository.getTotalSavings()).thenReturn(40.0);       // test-only non-legacy figure
        when(savingsRepository.getLegacyTotalSavings()).thenReturn(null);
        todaysEvents();

        Map<String, Object> metrics = service.getAdminDashboardMetrics();

        assertThat(metrics.get("totalSavings")).isEqualTo(40.0);
        assertThat(metrics.get("legacyUnverifiedTotal")).isEqualTo(0.0);
    }

    // ── /historical-savings ─────────────────────────────────────────────────

    @Test
    void historicalSavings_legacyDaysCarryOnlyLegacySavings_andTodayIsLegacy() {
        LocalDate today = LocalDate.now();
        LocalDate dayA = today.minusDays(5);
        LocalDate dayB = today.minusDays(2);
        when(savingsRepository.getAggregatedHistory(any())).thenReturn(List.of());
        List<Object[]> legacy = new ArrayList<>();
        legacy.add(new Object[] { dayA, 10.0, 2L });
        legacy.add(new Object[] { dayB, 5.0, 1L });
        when(savingsRepository.getLegacyAggregatedHistory(any())).thenReturn(legacy);
        todaysEvents(successEvent(0.3));

        List<Map<String, Object>> history = service.getAdminHistoricalSavings(7);

        assertThat(history).extracting(p -> p.get("date"))
                .containsExactly(dayA.toString(), dayB.toString(), today.toString());
        // No realized figure on any day: a day without one carries null, never 0.
        assertThat(history).allSatisfy(p -> assertThat(p.get("savings")).isNull());
        assertThat(history).extracting(p -> p.get("legacySavings")).containsExactly(10.0, 5.0, 0.3);
        assertThat(history).extracting(p -> p.get("instanceCount")).containsExactly(2L, 1L, 1L);
        verify(savingsRepository).getAggregatedHistory(today.minusDays(7));
        verify(savingsRepository).getLegacyAggregatedHistory(today.minusDays(7));
    }

    @Test
    void historicalSavings_aNonLegacyDayKeepsItsRealizedAmountApartFromLegacy() {
        LocalDate today = LocalDate.now();
        LocalDate day = today.minusDays(3);
        List<Object[]> realized = new ArrayList<>();
        realized.add(new Object[] { day, 12.0, 1L });                    // test-only non-legacy row
        when(savingsRepository.getAggregatedHistory(any())).thenReturn(realized);
        List<Object[]> legacy = new ArrayList<>();
        legacy.add(new Object[] { day, 4.0, 1L });
        when(savingsRepository.getLegacyAggregatedHistory(any())).thenReturn(legacy);
        todaysEvents();

        List<Map<String, Object>> history = service.getAdminHistoricalSavings(7);

        Map<String, Object> point = history.get(0);
        assertThat(point.get("date")).isEqualTo(day.toString());
        assertThat(point.get("savings")).isEqualTo(12.0);
        assertThat(point.get("legacySavings")).isEqualTo(4.0);
        assertThat(point.get("instanceCount")).isEqualTo(2L);
    }

    // ── /account-breakdown ──────────────────────────────────────────────────

    @Test
    void accountBreakdown_realizedFromNonLegacyRows_eventSumIsLegacy() {
        CloudAccount aws = account(1L, "AWS", null);
        when(cloudAccountRepository.findAll()).thenReturn(List.of(aws));
        when(savingsRepository.findByAccountIdAndDateBetweenAndLegacyFalseOrderByDateAsc(
                eq(1L), eq(ALL_TIME_START), any())).thenReturn(List.of());
        when(eventRepository.findByAccountIdOrderByEventTimeDesc(1L))
                .thenReturn(List.of(successEvent(0.5), successEvent(0.25)));

        List<Map<String, Object>> breakdown = service.getAdminAccountBreakdown();

        assertThat(breakdown).hasSize(1);
        Map<String, Object> entry = breakdown.get(0);
        assertThat(entry.get("accountId")).isEqualTo("000000000001");
        assertThat(entry.get("totalSavings")).isEqualTo(0.0);
        assertThat((Double) entry.get("legacyUnverifiedSavings")).isCloseTo(0.75, within(1e-9));
        assertThat(entry.get("instanceCount")).isEqualTo(2);
        verify(savingsRepository, never()).findByAccountIdAndDateBetweenOrderByDateAsc(anyLong(), any(), any());
    }

    // ── /customer-savings ───────────────────────────────────────────────────

    @Test
    void customerSavings_realizedFromNonLegacyRows_legacyPerProviderApart() {
        Client client = new Client();
        client.setId(9L);
        client.setName("Example Client");
        CloudAccount aws = account(1L, "AWS", client);
        CloudAccount gcp = account(2L, "GCP", client);
        when(redisCacheService.get(eq("autospotting-admin-customer-savings"),
                any(com.fasterxml.jackson.core.type.TypeReference.class))).thenReturn(Optional.empty());
        when(settingsRepository.findAllByEnabled(true))
                .thenReturn(List.of(new AutoSpottingSettings(aws), new AutoSpottingSettings(gcp)));
        when(savingsRepository.findByAccountIdAndDateBetweenAndLegacyFalseOrderByDateAsc(
                anyLong(), eq(ALL_TIME_START), any())).thenReturn(List.<DailySpotSavings>of());
        when(savingsRepository.sumLegacyAmount(eq(List.of(1L)), eq(ALL_TIME_START), any())).thenReturn(100.0);
        when(savingsRepository.sumLegacyAmount(eq(List.of(2L)), eq(ALL_TIME_START), any())).thenReturn(50.0);

        List<Map<String, Object>> result = service.getCustomerSpotSavings();

        assertThat(result).hasSize(1);
        Map<String, Object> entry = result.get(0);
        assertThat(entry.get("clientName")).isEqualTo("Example Client");
        assertThat(entry.get("awsSavings")).isEqualTo(0.0);
        assertThat(entry.get("gcpSavings")).isEqualTo(0.0);
        assertThat(entry.get("totalSavings")).isEqualTo(0.0);
        assertThat(entry.get("legacyUnverifiedAws")).isEqualTo(100.0);
        assertThat(entry.get("legacyUnverifiedGcp")).isEqualTo(50.0);
        assertThat(entry.get("legacyUnverifiedTotal")).isEqualTo(150.0);
        verify(savingsRepository, never()).findByAccountIdAndDateBetweenOrderByDateAsc(anyLong(), any(), any());
    }
}
