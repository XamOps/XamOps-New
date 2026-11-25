package com.xammer.cloud.xampulse.service.alert;

import com.xammer.cloud.xampulse.dto.XpAlertPreview;
import com.xammer.cloud.xampulse.dto.XpFiring;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class AlertBacktestTest {

    private static final long T0 = 1_800_000_000L / 60 * 60;

    /** One value per minute starting at T0; null = no data that minute. */
    private static NavigableMap<Long, Double> mins(Double... v) {
        NavigableMap<Long, Double> m = new TreeMap<>();
        for (int i = 0; i < v.length; i++) {
            if (v[i] != null) {
                m.put(T0 + i * 60L, v[i]);
            }
        }
        return m;
    }

    private static AlertPreviewEngine.Config cfg(String op, double t, int holdMin, AlertStateMachine.NoDataPolicy nd, int dedupeMin) {
        return new AlertPreviewEngine.Config(op, t, Duration.ofMinutes(holdMin), nd, Duration.ofMinutes(dedupeMin));
    }

    private static XpAlertPreview run(Map<String, NavigableMap<Long, Double>> v, int minutes, AlertPreviewEngine.Config c) {
        return AlertPreviewEngine.replay(v, List.of(), null, T0, T0 + (minutes - 1) * 60L, c, 6);
    }

    private static Instant at(int minute) {
        return Instant.ofEpochSecond(T0 + minute * 60L + 60); // evaluation of minute m happens at its end
    }

    @Test
    void forHoldsABreachBackAndTheFiringStartsWhenItElapses() {
        // breach 1..4 (fires at 3, recovers at 5), a one-minute blip at 11, breach again from 18 to the end
        XpAlertPreview p = run(Map.of("i-1", mins(1.0, 9.0, 9.0, 9.0, 9.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 9.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 9.0, 9.0, 9.0)),
                21, cfg(">", 5, 2, AlertStateMachine.NoDataPolicy.KEEP, 0));
        assertEquals(21, p.evaluatedPoints());
        assertEquals(2, p.firings().size(), "the one-minute blip at minute 11 is held back by 'for'");
        XpFiring f = p.firings().get(0);
        assertEquals(at(1), f.from(), "breach seen at the end of minute 1");
        assertEquals(at(3), f.firedAt(), "fires once it has lasted 2 minutes");
        assertEquals(at(5), f.to());
        assertEquals(9.0, f.peak());
        assertFalse(f.ongoing());
        XpFiring g = p.firings().get(1);
        assertEquals(at(20), g.firedAt());
        assertTrue(g.ongoing(), "still firing at the end of the range");
        assertEquals(2, p.wouldNotify());
        assertEquals(1, p.resolved());
    }

    @Test
    void aReBreachJustAfterRecoveringReopensQuietlyAndRepeatsInsideTheDedupeWindowAreNotSent() {
        // Fires at 0 and recovers at 1. Breaches again at 2, the evaluation right after recovering: the flap guard
        // re-opens it without a notification. Recovers at 3; a breach at 12 is a new firing, but inside the
        // 30-minute repeat window of the one sent at 0, so it is not sent either.
        Double[] v = new Double[14];
        java.util.Arrays.fill(v, 1.0);
        v[0] = 9.0; v[2] = 9.0; v[12] = 9.0;
        XpAlertPreview p = run(Map.of("i-1", mins(v)), 14, cfg(">", 5, 0, AlertStateMachine.NoDataPolicy.KEEP, 30));
        assertEquals(3, p.firings().size());
        assertEquals(1, p.wouldNotify(), "only the first firing is sent");
        assertEquals(1, p.quietReopens());
        assertEquals(1, p.suppressedRepeats());
        assertEquals(List.of(true, false, false), p.firings().stream().map(XpFiring::notified).toList());
        assertEquals(3, p.resolved());
    }

    @Test
    void theFlapGuardOnlyCoversTheEvaluationRightAfterRecovering() {
        // Same as live alerting: RESOLVED becomes OK at the next healthy evaluation, after which a breach is new.
        XpAlertPreview p = run(Map.of("i-1", mins(9.0, 1.0, 9.0, 9.0, 1.0, 1.0, 9.0)), 7, cfg(">", 5, 0, AlertStateMachine.NoDataPolicy.KEEP, 0));
        assertEquals(3, p.firings().size());
        assertEquals(1, p.quietReopens(), "minute 2 re-opens quietly");
        assertEquals(2, p.wouldNotify(), "minute 6 follows an OK evaluation: a new, notified firing");
    }

    @Test
    void missingDataFollowsTheRulesPolicy() {
        Map<String, NavigableMap<Long, Double>> v = Map.of("i-1", mins(9.0, null, null, 9.0, 1.0));
        assertEquals(1, run(v, 5, cfg(">", 5, 3, AlertStateMachine.NoDataPolicy.KEEP, 0)).firings().size(),
                "KEEP: the breach stays pending across the gap and 'for' elapses");
        assertEquals(0, run(v, 5, cfg(">", 5, 3, AlertStateMachine.NoDataPolicy.OK, 0)).firings().size(),
                "OK: the gap clears it");
        XpAlertPreview alert = run(Map.of("i-1", mins(1.0, null, null, null, 1.0)), 5, cfg(">", 5, 2, AlertStateMachine.NoDataPolicy.ALERT, 0));
        assertEquals(1, alert.firings().size(), "ALERT: three minutes without data is a breach");
        assertNull(alert.firings().get(0).peak(), "no measured value during it");
        assertEquals(0, run(Map.of("i-1", mins(null, null, 9.0)), 3, cfg(">", 5, 0, AlertStateMachine.NoDataPolicy.ALERT, 0)).firings().get(0).from().compareTo(at(2)),
                "a group the evaluator has not seen yet has no state: nothing happens before its first data");
    }

    @Test
    void logCountsCoverTheRulesInstancesWithZeroWhenNothingMatched() {
        // "fewer than 1 matching line in the window" fires for a host that logged nothing at all.
        XpAlertPreview p = AlertPreviewEngine.replay(Map.of("i-1", mins(4.0, 4.0, 4.0)), List.of("i-1", "i-2"), 0.0, T0, T0 + 120,
                cfg("<", 1, 0, AlertStateMachine.NoDataPolicy.KEEP, 0), 6);
        assertEquals(1, p.firings().size());
        assertEquals("i-2", p.firings().get(0).group().get("group"));
        assertEquals(0.0, p.firings().get(0).peak(), "worst value for '<' is the lowest");
        assertEquals(2, p.groups());
        assertEquals("i-2", p.series().get(0).group(), "the group that fired is shown first");
    }

    @Test
    void heartbeatAgesAreSecondsSinceTheLastMinuteWithDataWithin24Hours() {
        List<Map<String, Object>> rows = List.of(Map.of("g0", "i-1", "t", T0 - 120), Map.of("g0", "i-1", "t", T0 + 60),
                Map.of("g0", "i-old", "t", T0 - 90_000L));
        Map<String, NavigableMap<Long, Double>> ages = AlertPreviewService.ages(rows, List.of("i-1", "i-old", "i-never"), T0, T0 + 120);
        assertEquals(180.0, ages.get("i-1").get(T0), "evaluated at T0+60, last data T0-120");
        assertEquals(60.0, ages.get("i-1").get(T0 + 60));
        assertEquals(120.0, ages.get("i-1").get(T0 + 120));
        assertTrue(ages.get("i-old").isEmpty(), "older than 24 hours is no data, as the evaluator reads it");
        assertTrue(ages.get("i-never").isEmpty());
    }
}
