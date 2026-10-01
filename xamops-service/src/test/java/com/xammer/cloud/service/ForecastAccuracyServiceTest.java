package com.xammer.cloud.service;

import com.xammer.cloud.domain.ForecastAccuracyRecord;
import com.xammer.cloud.dto.ForecastAccuracyMetrics;
import com.xammer.cloud.dto.ForecastDto;
import com.xammer.cloud.dto.HistoricalCostDto;
import com.xammer.cloud.repository.ForecastAccuracyRepository;
import com.xammer.cloud.service.gcp.GcpCostService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ForecastAccuracyService — focuses on edge cases:
 * zero actuals, missing actuals, dedup, NaN/negative guards, MAPE/coverage math.
 */
@ExtendWith(MockitoExtension.class)
class ForecastAccuracyServiceTest {

    @Mock ForecastAccuracyRepository repository;
    @Mock CostService costService;
    @Mock GcpCostService gcpCostService;
    @Mock CostDataSnapshotService snapshotService;
    @Mock ForecastingService forecastingService;
    /** Azure's daily actuals source, read by fetchActuals for PROVIDER_AZURE records. */
    @Mock com.xammer.cloud.service.azure.AzureBillingDataIngestionService azureBillingService;

    @Captor ArgumentCaptor<List<ForecastAccuracyRecord>> saveCaptor;

    ForecastAccuracyService service;

    @BeforeEach
    void setUp() {
        service = new ForecastAccuracyService(
                repository, costService, gcpCostService, snapshotService, forecastingService,
                azureBillingService);
    }

    private ForecastDto point(String ds, double yhat, double lower, double upper) {
        ForecastDto p = new ForecastDto();
        p.setDs(ds);
        p.setYhat(yhat);
        p.setYhatLower(lower);
        p.setYhatUpper(upper);
        return p;
    }

    // ---------- normalizeService ----------

    @Test
    void normalizeService_collapsesNullEmptyAllToALL() {
        assertEquals("ALL", ForecastAccuracyService.normalizeService(null));
        assertEquals("ALL", ForecastAccuracyService.normalizeService(""));
        assertEquals("ALL", ForecastAccuracyService.normalizeService("   "));
        assertEquals("ALL", ForecastAccuracyService.normalizeService("all"));
        assertEquals("ALL", ForecastAccuracyService.normalizeService("ALL"));
        assertEquals("Amazon EC2", ForecastAccuracyService.normalizeService("Amazon EC2"));
    }

    // ---------- recording ----------

    @Test
    void recordPredictions_onlyStoresFutureDates() {
        when(repository.findExistingTargetDates(any(), any(), any(), any()))
                .thenReturn(Collections.emptyList());
        LocalDate today = LocalDate.now();

        List<ForecastDto> points = Arrays.asList(
                point(today.minusDays(1).toString(), 10, 8, 12),  // past — skip
                point(today.toString(), 10, 8, 12),               // today — skip
                point(today.plusDays(1).toString(), 11, 9, 13),   // future — keep
                point(today.plusDays(2).toString(), 12, 10, 14)   // future — keep
        );

        service.recordPredictions("AWS", "acct-1", "ALL", points);

        verify(repository).saveAll(saveCaptor.capture());
        List<ForecastAccuracyRecord> saved = saveCaptor.getValue();
        assertEquals(2, saved.size());
        assertTrue(saved.stream().allMatch(r -> r.getTargetDate().isAfter(today)));
    }

    @Test
    void recordPredictions_skipsDuplicates() {
        LocalDate today = LocalDate.now();
        LocalDate dup = today.plusDays(1);
        when(repository.findExistingTargetDates(any(), any(), any(), any()))
                .thenReturn(new ArrayList<>(Collections.singletonList(dup)));

        List<ForecastDto> points = Arrays.asList(
                point(dup.toString(), 11, 9, 13),                 // already exists — skip
                point(today.plusDays(2).toString(), 12, 10, 14)   // new — keep
        );

        service.recordPredictions("AWS", "acct-1", "ALL", points);

        verify(repository).saveAll(saveCaptor.capture());
        assertEquals(1, saveCaptor.getValue().size());
    }

    @Test
    void recordPredictions_handlesUnparseableDatesAndNaN() {
        when(repository.findExistingTargetDates(any(), any(), any(), any()))
                .thenReturn(Collections.emptyList());
        LocalDate today = LocalDate.now();

        List<ForecastDto> points = Arrays.asList(
                point("not-a-date", 10, 8, 12),                    // skip
                point(today.plusDays(1).toString(), Double.NaN, Double.NEGATIVE_INFINITY, 5) // sanitized
        );

        service.recordPredictions("AWS", "acct-1", null, points);

        verify(repository).saveAll(saveCaptor.capture());
        List<ForecastAccuracyRecord> saved = saveCaptor.getValue();
        assertEquals(1, saved.size());
        ForecastAccuracyRecord r = saved.get(0);
        assertEquals(0.0, r.getPredictedCost());      // NaN -> 0
        assertTrue(r.getPredictedLower() >= 0);        // -Inf -> 0
        assertEquals("ALL", r.getServiceName());       // null normalized
    }

    @Test
    void recordPredictions_emptyOrNullIsNoop() {
        service.recordPredictions("AWS", "acct-1", "ALL", null);
        service.recordPredictions("AWS", "acct-1", "ALL", Collections.emptyList());
        verify(repository, never()).saveAll(anyList());
    }

    // ---------- evaluation ----------

    private ForecastAccuracyRecord pending(String provider, String acct, String svc,
                                           LocalDate target, double predicted,
                                           double lower, double upper) {
        LocalDate forecastDate = target.minusDays(5);
        ForecastAccuracyRecord r = new ForecastAccuracyRecord(
                provider, acct, svc, forecastDate, target, predicted, lower, upper);
        return r;
    }

    @Test
    void evaluatePending_computesErrorsAndCoverage_AWS() throws Exception {
        LocalDate t1 = LocalDate.now().minusDays(2);
        LocalDate t2 = LocalDate.now().minusDays(1);

        ForecastAccuracyRecord r1 = pending("AWS", "acct-1", "ALL", t1, 100, 80, 120);
        ForecastAccuracyRecord r2 = pending("AWS", "acct-1", "ALL", t2, 100, 80, 120);

        when(repository.findByStatusAndTargetDateLessThanEqual(eq("PENDING"), any()))
                .thenReturn(new ArrayList<>(Arrays.asList(r1, r2)));

        // Actuals: t1 = 110 (within band, APE=9.09%), t2 = 150 (outside band, APE=33.3%)
        HistoricalCostDto hist = new HistoricalCostDto(
                new ArrayList<>(Arrays.asList(t1.toString(), t2.toString())),
                new ArrayList<>(Arrays.asList(110.0, 150.0)));
        when(costService.getHistoricalCost(eq("acct-1"), isNull(), isNull(), anyInt(), eq(false)))
                .thenReturn(CompletableFuture.completedFuture(hist));

        int evaluated = service.evaluatePending();

        assertEquals(2, evaluated);
        assertEquals("EVALUATED", r1.getStatus());
        assertEquals(110.0, r1.getActualCost());
        assertEquals(10.0, r1.getAbsoluteError());
        assertEquals(9.09, r1.getPercentageError(), 0.01);
        assertTrue(r1.getWithinConfidence());

        assertEquals("EVALUATED", r2.getStatus());
        assertEquals(33.33, r2.getPercentageError(), 0.01);
        assertFalse(r2.getWithinConfidence()); // 150 > upper 120
    }

    @Test
    void evaluatePending_zeroActual_predictedNonZero_leavesPercentageNull() throws Exception {
        LocalDate t1 = LocalDate.now().minusDays(2);
        ForecastAccuracyRecord r = pending("AWS", "acct-1", "ALL", t1, 50, 40, 60);

        when(repository.findByStatusAndTargetDateLessThanEqual(eq("PENDING"), any()))
                .thenReturn(new ArrayList<>(Collections.singletonList(r)));

        HistoricalCostDto hist = new HistoricalCostDto(
                new ArrayList<>(Collections.singletonList(t1.toString())),
                new ArrayList<>(Collections.singletonList(0.0)));
        when(costService.getHistoricalCost(any(), any(), any(), anyInt(), anyBoolean()))
                .thenReturn(CompletableFuture.completedFuture(hist));

        service.evaluatePending();

        assertEquals("EVALUATED", r.getStatus());
        assertEquals(0.0, r.getActualCost());
        assertEquals(50.0, r.getAbsoluteError());
        assertNull(r.getPercentageError()); // undefined APE
    }

    @Test
    void evaluatePending_bothZero_isPerfect() throws Exception {
        LocalDate t1 = LocalDate.now().minusDays(2);
        ForecastAccuracyRecord r = pending("AWS", "acct-1", "ALL", t1, 0, 0, 0);

        when(repository.findByStatusAndTargetDateLessThanEqual(eq("PENDING"), any()))
                .thenReturn(new ArrayList<>(Collections.singletonList(r)));

        HistoricalCostDto hist = new HistoricalCostDto(
                new ArrayList<>(Collections.singletonList(t1.toString())),
                new ArrayList<>(Collections.singletonList(0.0)));
        when(costService.getHistoricalCost(any(), any(), any(), anyInt(), anyBoolean()))
                .thenReturn(CompletableFuture.completedFuture(hist));

        service.evaluatePending();

        assertEquals(0.0, r.getPercentageError());
        assertTrue(r.getWithinConfidence());
    }

    @Test
    void evaluatePending_missingActual_recentStaysPending() throws Exception {
        LocalDate t1 = LocalDate.now().minusDays(2); // recent — should stay pending
        ForecastAccuracyRecord r = pending("AWS", "acct-1", "ALL", t1, 50, 40, 60);

        when(repository.findByStatusAndTargetDateLessThanEqual(eq("PENDING"), any()))
                .thenReturn(new ArrayList<>(Collections.singletonList(r)));

        // No actuals returned
        when(costService.getHistoricalCost(any(), any(), any(), anyInt(), anyBoolean()))
                .thenReturn(CompletableFuture.completedFuture(
                        new HistoricalCostDto(new ArrayList<>(), new ArrayList<>())));

        int evaluated = service.evaluatePending();

        assertEquals(0, evaluated);
        assertEquals("PENDING", r.getStatus());
        assertEquals(1, r.getEvalAttempts());
    }

    @Test
    void evaluatePending_missingActual_oldGetsSkipped() throws Exception {
        LocalDate old = LocalDate.now().minusDays(40); // beyond skip window
        ForecastAccuracyRecord r = pending("AWS", "acct-1", "ALL", old, 50, 40, 60);

        when(repository.findByStatusAndTargetDateLessThanEqual(eq("PENDING"), any()))
                .thenReturn(new ArrayList<>(Collections.singletonList(r)));
        when(costService.getHistoricalCost(any(), any(), any(), anyInt(), anyBoolean()))
                .thenReturn(CompletableFuture.completedFuture(
                        new HistoricalCostDto(new ArrayList<>(), new ArrayList<>())));

        service.evaluatePending();

        assertEquals("SKIPPED", r.getStatus());
        assertNotNull(r.getEvaluatedAt());
    }

    @Test
    void evaluatePending_emptyPending_returnsZero() {
        when(repository.findByStatusAndTargetDateLessThanEqual(eq("PENDING"), any()))
                .thenReturn(Collections.emptyList());
        assertEquals(0, service.evaluatePending());
        verify(repository, never()).saveAll(anyList());
    }

    // ---------- metrics ----------

    @Test
    void getMetrics_insufficientWhenNoRecords() {
        when(repository.findEvaluatedForAccount(any(), any(), any()))
                .thenReturn(Collections.emptyList());

        ForecastAccuracyMetrics m = service.getMetrics("AWS", "acct-1", null, 90);
        assertEquals("INSUFFICIENT_DATA", m.getDataQuality());
        assertEquals(0, m.getEvaluatedCount());
    }

    @Test
    void getMetrics_computesMapeMaeCoverage() {
        List<ForecastAccuracyRecord> records = new ArrayList<>();
        // 8 records so we exceed the INSUFFICIENT threshold (7)
        for (int i = 0; i < 8; i++) {
            ForecastAccuracyRecord r = pending("AWS", "acct-1", "ALL",
                    LocalDate.now().minusDays(i + 1), 100, 80, 120);
            r.setStatus("EVALUATED");
            r.setActualCost(110.0);
            r.setAbsoluteError(10.0);
            r.setPercentageError(10.0); // 10% APE each
            r.setWithinConfidence(i % 2 == 0); // half within band
            records.add(r);
        }
        when(repository.findEvaluatedForAccountService(eq("AWS"), eq("acct-1"), eq("ALL"), any()))
                .thenReturn(records);

        ForecastAccuracyMetrics m = service.getMetrics("AWS", "acct-1", "ALL", 90);

        assertEquals(8, m.getEvaluatedCount());
        assertEquals(10.0, m.getMape());
        assertEquals(10.0, m.getMae());
        assertEquals(90.0, m.getAccuracyScore()); // 100 - 10
        assertEquals(50.0, m.getConfidenceCoverage());
        assertEquals("GOOD", m.getDataQuality());
    }

    @Test
    void getMetrics_zeroActualRecordsExcludedFromMape() {
        List<ForecastAccuracyRecord> records = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            ForecastAccuracyRecord r = pending("AWS", "acct-1", "ALL",
                    LocalDate.now().minusDays(i + 1), 50, 0, 100);
            r.setStatus("EVALUATED");
            r.setActualCost(0.0);
            r.setAbsoluteError(50.0);
            r.setPercentageError(null); // undefined — excluded from MAPE
            r.setWithinConfidence(true);
            records.add(r);
        }
        when(repository.findEvaluatedForAccount(eq("AWS"), eq("acct-1"), any()))
                .thenReturn(records);

        ForecastAccuracyMetrics m = service.getMetrics("AWS", "acct-1", null, 90);

        assertNull(m.getMape());             // no defined APEs
        assertEquals(50.0, m.getMae());      // MAE still computed
        assertEquals("FAIR", m.getDataQuality());
    }

    // ---------- backfill ----------

    @Test
    void backfill_replaysSnapshotsAndScoresImmediately() {
        LocalDate yesterday = LocalDate.now().minusDays(1);

        // Provide ~210 days of daily actuals across the relevant months so history >= 14
        // and the forecast horizon days have actuals to score against.
        java.util.Map<YearMonth, List<CostDataSnapshotService.DailyCostEntry>> byMonth = new java.util.HashMap<>();
        for (int i = 0; i < 210; i++) {
            LocalDate d = yesterday.minusDays(i);
            YearMonth ym = YearMonth.from(d);
            byMonth.computeIfAbsent(ym, k -> new ArrayList<>())
                    .add(new CostDataSnapshotService.DailyCostEntry(d.toString(), 100.0));
        }
        when(snapshotService.readDailyCost(eq("acct-1"), any(YearMonth.class)))
                .thenAnswer(inv -> Optional.ofNullable(byMonth.get(inv.getArgument(1))));

        // Forecast returns the days right after each origin with a prediction of 100.
        when(forecastingService.generateForecastFromHistory(anyList(), anyInt()))
                .thenAnswer(inv -> {
                    List<java.util.Map<String, Object>> hist = inv.getArgument(0);
                    int periods = inv.getArgument(1);
                    String lastDs = (String) hist.get(hist.size() - 1).get("ds");
                    LocalDate origin = LocalDate.parse(lastDs);
                    List<ForecastDto> out = new ArrayList<>();
                    for (int p = 1; p <= periods; p++) {
                        out.add(point(origin.plusDays(p).toString(), 100, 80, 120));
                    }
                    return out;
                });

        when(repository.findExistingTargetDates(any(), any(), any(), any()))
                .thenReturn(Collections.emptyList());
        // metrics query at the end
        when(repository.findEvaluatedForAccountService(eq("AWS"), eq("acct-1"), eq("ALL"), any()))
                .thenAnswer(inv -> new ArrayList<>()); // not asserting metrics here

        ForecastAccuracyMetrics m = service.backfillFromSnapshots("acct-1", 30, 2);

        // Two origins × up to 30 scored days each were saved as EVALUATED.
        verify(repository, atLeastOnce()).saveAll(saveCaptor.capture());
        List<ForecastAccuracyRecord> saved = saveCaptor.getAllValues().stream()
                .flatMap(List::stream).collect(java.util.stream.Collectors.toList());
        assertFalse(saved.isEmpty());
        assertTrue(saved.stream().allMatch(r -> "EVALUATED".equals(r.getStatus())));
        assertTrue(saved.stream().allMatch(r -> r.getActualCost() != null));
        // predicted 100 vs actual 100 → 0% error, within band
        assertTrue(saved.stream().allMatch(r -> r.getPercentageError() != null && r.getPercentageError() == 0.0));
        assertTrue(saved.stream().allMatch(r -> Boolean.TRUE.equals(r.getWithinConfidence())));
        assertNotNull(m);
    }

    @Test
    void backfill_insufficientSnapshots_returnsInsufficient() {
        // No snapshots → empty actuals
        when(snapshotService.readDailyCost(any(), any(YearMonth.class)))
                .thenReturn(Optional.empty());

        ForecastAccuracyMetrics m = service.backfillFromSnapshots("acct-1", 30, 4);

        assertEquals("INSUFFICIENT_DATA", m.getDataQuality());
        verify(forecastingService, never()).generateForecastFromHistory(anyList(), anyInt());
    }
}
