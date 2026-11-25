package com.xammer.cloud.xampulse.service.insight;

import com.xammer.cloud.xampulse.dto.XpEvent;
import com.xammer.cloud.xampulse.dto.XpForecast;
import com.xammer.cloud.xampulse.dto.XpHealth;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class InsightMathTest {

    // ---------------- health score ----------------

    private final HealthScoreService health = new HealthScoreService();

    @Test
    void noDataAnywhereIsNullNeverHundred() {
        var r = health.evaluate(new HealthScoreService.Signals(null, null, null, null, null));
        assertNull(r.score());
        assertEquals(XpHealth.NO_DATA, r.health());
    }

    @Test
    void healthyHostScoresHundredAndAllKnownLanesAreOk() {
        var r = health.evaluate(new HealthScoreService.Signals(20.0, 40.0, 50.0, 30L, 0.0));
        assertEquals(100, r.score());
        assertEquals(XpHealth.OK, r.health());
    }

    @Test
    void worstOfCapKeepsACriticalHostFromLookingHealthy() {
        var r = health.evaluate(new HealthScoreService.Signals(99.0, 10.0, 10.0, 10L, 0.0));
        assertEquals(XpHealth.CRIT, r.health());
        assertTrue(r.score() <= 59, "score was " + r.score());
        assertEquals("CPU at 99%", r.reason());
        var w = health.evaluate(new HealthScoreService.Signals(82.0, 10.0, 10.0, 10L, 0.0));
        assertEquals(XpHealth.WARN, w.health());
        assertTrue(w.score() <= 89);
    }

    @Test
    void staleDataIsCriticalEvenWhenLastValuesLookFine() {
        var r = health.evaluate(new HealthScoreService.Signals(5.0, 5.0, 5.0, 20L * 60, 0.0));
        assertEquals(XpHealth.CRIT, r.health());
        assertTrue(r.reason().contains("No data for 20 minutes"));
    }

    @Test
    void scoreNeverGoesBelowZero() {
        var r = health.evaluate(new HealthScoreService.Signals(100.0, 100.0, 100.0, 9999L, 500.0));
        assertEquals(0, r.score());
    }

    // ---------------- attention ranking ----------------

    private final AttentionRanker ranker = new AttentionRanker();
    private final Instant now = Instant.parse("2026-01-01T12:00:00Z");

    @Test
    void rankFollowsSeverityBlastRadiusDurationAndConfidence() {
        var crit = new AttentionRanker.Candidate("crit", "CRITICAL", "HIGH", 1, now.minusSeconds(600), false, false);
        var wide = new AttentionRanker.Candidate("wide", "MEDIUM", "HIGH", 10, now.minusSeconds(600), false, false);
        var lowConf = new AttentionRanker.Candidate("lowconf", "CRITICAL", "LOW", 1, now.minusSeconds(600), false, false);
        var ranked = ranker.rank(List.of(lowConf, wide, crit), now, 10);
        assertEquals(List.of("wide", "crit", "lowconf"), ranked.stream().map(r -> r.candidate().id()).toList());
        // 8 * 1 * log10(11) vs 2 * 10 * log10(11)
        assertTrue(ranked.get(0).score() > ranked.get(1).score());
    }

    @Test
    void acknowledgedAndSilencedItemsDropOutAndMaxIsHonoured() {
        var a = new AttentionRanker.Candidate("a", "HIGH", "HIGH", 1, now.minusSeconds(60), true, false);
        var s = new AttentionRanker.Candidate("s", "HIGH", "HIGH", 1, now.minusSeconds(60), false, true);
        var k1 = new AttentionRanker.Candidate("k1", "HIGH", "HIGH", 1, now.minusSeconds(60), false, false);
        var k2 = new AttentionRanker.Candidate("k2", "LOW", "HIGH", 1, now.minusSeconds(60), false, false);
        assertEquals(List.of("k1"), ranker.rank(List.of(a, s, k1, k2), now, 1).stream().map(r -> r.candidate().id()).toList());
        assertEquals(2, ranker.rank(List.of(a, s, k1, k2), now, 10).size());
    }

    @Test
    void longerRunningItemsOutrankFresherOnesOfTheSameKind() {
        var old = new AttentionRanker.Candidate("old", "HIGH", "HIGH", 1, now.minusSeconds(3 * 3600), false, false);
        var fresh = new AttentionRanker.Candidate("fresh", "HIGH", "HIGH", 1, now.minusSeconds(120), false, false);
        assertEquals("old", ranker.rank(List.of(fresh, old), now, 5).get(0).candidate().id());
    }

    // ---------------- narrative ----------------

    private final NarrativeBuilder narrative = new NarrativeBuilder();

    @Test
    void narrativeUsesRealNumbersAndStaysWithinThreeSentences() {
        var out = narrative.build(new NarrativeBuilder.Facts(10, 8, 1, 2, 4.5, 2, "i-0abc", "CPU at 97%"));
        assertTrue(out.size() >= 1 && out.size() <= 3);
        assertTrue(out.get(0).contains("8 of 10"));
        assertTrue(out.get(1).contains("1 server is critical") && out.get(1).contains("i-0abc") && out.get(1).contains("CPU at 97%"));
        assertTrue(out.get(2).contains("2 open incidents") && out.get(2).contains("4.5"));
    }

    @Test
    void narrativeIsHonestWhenNothingIsConnectedOrReporting() {
        assertTrue(narrative.build(new NarrativeBuilder.Facts(0, 0, 0, 0, 0, 0, null, null)).get(0).contains("No servers are connected"));
        assertTrue(narrative.build(new NarrativeBuilder.Facts(5, 0, 0, 0, 0, 0, null, null)).get(0).contains("None of your 5"));
        assertTrue(narrative.build(new NarrativeBuilder.Facts(5, 5, 0, 0, 0, 0, null, null)).get(1).contains("healthy"));
    }

    // ---------------- baseline / anomaly ----------------

    @Test
    void madBaselineAndBand() {
        List<BaselineMath.Sample> samples = new ArrayList<>();
        // Same hour-of-week on 6 consecutive weeks (2026-01-05 is a Monday).
        double[] vals = {10, 12, 11, 10, 13, 11};
        for (int w = 0; w < vals.length; w++) {
            samples.add(new BaselineMath.Sample(Instant.parse("2026-01-05T09:30:00Z").plusSeconds(w * 7L * 86_400), vals[w]));
        }
        int how = BaselineMath.hourOfWeek(Instant.parse("2026-01-05T09:00:00Z"));
        assertEquals(9, how);
        var base = BaselineMath.compute(samples).get(how);
        assertEquals(11.0, base.median(), 1e-9);
        assertEquals(1.0, base.mad(), 1e-9);
        double[] band = BaselineMath.band(base, 4);
        assertEquals(11 - 4 * 1.4826, band[0], 1e-6);
        assertEquals(11 + 4 * 1.4826, band[1], 1e-6);
        assertFalse(BaselineMath.isAnomalous(14, base, 4));
        assertTrue(BaselineMath.isAnomalous(30, base, 4));
        assertTrue(BaselineMath.isAnomalous(-5, base, 4));
    }

    @Test
    void coldStartHasNoBaselineAndNoBand() {
        List<BaselineMath.Sample> two = List.of(new BaselineMath.Sample(Instant.parse("2026-01-05T09:00:00Z"), 5),
                new BaselineMath.Sample(Instant.parse("2026-01-12T09:00:00Z"), 6));
        assertTrue(BaselineMath.compute(two).isEmpty());
        assertNull(BaselineMath.band(null, 4));
        assertFalse(BaselineMath.isAnomalous(1e9, null, 4));
    }

    @Test
    void flatHistoryStillGetsANonZeroWidthBand() {
        List<BaselineMath.Sample> flat = new ArrayList<>();
        for (int w = 0; w < 4; w++) {
            flat.add(new BaselineMath.Sample(Instant.parse("2026-01-05T09:00:00Z").plusSeconds(w * 7L * 86_400), 50));
        }
        var base = BaselineMath.compute(flat).get(9);
        double[] band = BaselineMath.band(base, 4);
        assertTrue(band[1] > band[0]);
        assertFalse(BaselineMath.isAnomalous(50, base, 4));
        assertTrue(new XamPulseAnomalyDetector().isAnomalous(80, base));
    }

    // ---------------- forecast ----------------

    private final ForecastService forecast = new ForecastService();

    private List<ForecastService.Point> line(double start, double slope, int days, double noise, long seed) {
        Random r = new Random(seed);
        List<ForecastService.Point> pts = new ArrayList<>();
        for (int d = 0; d < days; d++) {
            pts.add(new ForecastService.Point(d, start + slope * d + (noise == 0 ? 0 : (r.nextDouble() - 0.5) * noise)));
        }
        return pts;
    }

    @Test
    void knownSlopeGivesDaysToFullWithAnInterval() {
        XpForecast f = forecast.forecast(1L, "i-1", "/var", line(50, 2, 14, 0.2, 7));
        // last point ~ 76%, slope 2/day => ~12 days
        assertNotNull(f.daysToFull());
        assertEquals(12.0, f.daysToFull(), 1.0);
        assertTrue(f.r2() > 0.95);
        assertTrue(f.intervalDays().get(0) <= f.daysToFull() && f.daysToFull() <= f.intervalDays().get(1));
        assertTrue(f.message().contains("/var") && f.message().contains("i-1"));
    }

    @Test
    void noisyDataGivesNoReliableTrendNotADate() {
        XpForecast f = forecast.forecast(1L, "i-1", "/", line(50, 0.0, 14, 60, 3));
        assertNull(f.daysToFull());
        assertNull(f.intervalDays());
        assertTrue(f.message().toLowerCase().contains("no reliable trend") || f.message().toLowerCase().contains("flat"));
    }

    @Test
    void tooFewPointsAndFallingUsageNeverInventAFillDate() {
        assertNull(forecast.forecast(1L, "i", "/", line(10, 1, 3, 0, 1)).daysToFull());
        XpForecast falling = forecast.forecast(1L, "i", "/", line(80, -1, 14, 0, 1));
        assertNull(falling.daysToFull());
        assertTrue(falling.message().contains("flat or falling"));
    }

    // ---------------- what changed ----------------

    @Test
    void whatChangedRanksNearbyColocatedDeploysFirst() {
        Instant at = Instant.parse("2026-01-01T12:00:00Z");
        var deploy = new XpEvent(at.minusSeconds(120), "DEPLOY", 1L, "i-1", "Deploy v42", null);
        var otherHost = new XpEvent(at.minusSeconds(120), "DEPLOY", 1L, "i-2", "Deploy v42 on other host", null);
        var old = new XpEvent(at.minusSeconds(3000), "AGENT", 1L, "i-1", "Agent restarted", null);
        var future = new XpEvent(at.plusSeconds(60), "DEPLOY", 1L, "i-1", "After the problem", null);
        var tooOld = new XpEvent(at.minusSeconds(7200), "DEPLOY", 1L, "i-1", "Too old", null);
        var ranked = new WhatChangedService().rank(at, "i-1", List.of(old, otherHost, deploy, future, tooOld), 60, 2);
        assertEquals("Deploy v42", ranked.get(0).event().title());
        assertEquals(3, ranked.size());
        assertTrue(ranked.stream().noneMatch(r -> r.event().title().equals("After the problem") || r.event().title().equals("Too old")));
    }
}
