package com.xammer.cloud.xampulse.service.alert;

import com.xammer.cloud.xampulse.service.alert.AlertStateMachine.*;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class AlertStateMachineTest {

    private final Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
    private final Duration hold5 = Duration.ofMinutes(5);

    private Transition step(Current c, Outcome o, NoDataPolicy p, Duration hold, Instant now) {
        return AlertStateMachine.step(c, o, p, hold, now);
    }

    private Current cur(Transition t) {
        return new Current(t.state(), t.since());
    }

    @Test
    void breachHoldsInPendingUntilTheForDurationElapsesThenFiresOnce() {
        Transition t1 = step(Current.initial(t0), Outcome.BREACH, NoDataPolicy.KEEP, hold5, t0);
        assertEquals(State.PENDING, t1.state());
        assertFalse(t1.fire());
        Transition t2 = step(cur(t1), Outcome.BREACH, NoDataPolicy.KEEP, hold5, t0.plusSeconds(120));
        assertEquals(State.PENDING, t2.state());
        assertEquals(t0, t2.since(), "pending keeps its original start");
        Transition t3 = step(cur(t2), Outcome.BREACH, NoDataPolicy.KEEP, hold5, t0.plusSeconds(300));
        assertEquals(State.FIRING, t3.state());
        assertTrue(t3.fire());
        Transition t4 = step(cur(t3), Outcome.BREACH, NoDataPolicy.KEEP, hold5, t0.plusSeconds(360));
        assertEquals(State.FIRING, t4.state());
        assertFalse(t4.fire(), "edge-triggered: no repeat notification while firing");
    }

    @Test
    void zeroForFiresImmediately() {
        Transition t = step(Current.initial(t0), Outcome.BREACH, NoDataPolicy.KEEP, Duration.ZERO, t0);
        assertEquals(State.FIRING, t.state());
        assertTrue(t.fire());
    }

    @Test
    void recoveryBeforeTheHoldNeverNotifies() {
        Transition p = step(Current.initial(t0), Outcome.BREACH, NoDataPolicy.KEEP, hold5, t0);
        Transition ok = step(cur(p), Outcome.OK, NoDataPolicy.KEEP, hold5, t0.plusSeconds(60));
        assertEquals(State.OK, ok.state());
        assertFalse(ok.fire());
        assertFalse(ok.resolve());
    }

    @Test
    void resolveIsEdgeTriggeredAndFiresOnce() {
        Current firing = new Current(State.FIRING, t0);
        Transition r = step(firing, Outcome.OK, NoDataPolicy.KEEP, hold5, t0.plusSeconds(600));
        assertEquals(State.RESOLVED, r.state());
        assertTrue(r.resolve());
        Transition after = step(cur(r), Outcome.OK, NoDataPolicy.KEEP, hold5, t0.plusSeconds(660));
        assertEquals(State.OK, after.state());
        assertFalse(after.resolve());
    }

    @Test
    void flapGuardReopensQuietlyWithinFiveMinutesOfResolution() {
        Current resolved = new Current(State.RESOLVED, t0);
        Transition quick = step(resolved, Outcome.BREACH, NoDataPolicy.KEEP, hold5, t0.plusSeconds(120));
        assertEquals(State.FIRING, quick.state());
        assertFalse(quick.fire());
        assertTrue(quick.quietReopen());
        Transition slow = step(resolved, Outcome.BREACH, NoDataPolicy.KEEP, Duration.ZERO, t0.plusSeconds(400));
        assertTrue(slow.fire());
        assertFalse(slow.quietReopen());
    }

    @Test
    void noDataPolicies() {
        Current ok = Current.initial(t0);
        assertEquals(State.PENDING, step(ok, Outcome.NO_DATA, NoDataPolicy.ALERT, hold5, t0).state());
        assertTrue(step(ok, Outcome.NO_DATA, NoDataPolicy.ALERT, Duration.ZERO, t0).fire());
        assertEquals(State.OK, step(ok, Outcome.NO_DATA, NoDataPolicy.OK, hold5, t0).state());
        Current firing = new Current(State.FIRING, t0);
        Transition keep = step(firing, Outcome.NO_DATA, NoDataPolicy.KEEP, hold5, t0.plusSeconds(60));
        assertEquals(State.FIRING, keep.state());
        assertFalse(keep.resolve());
        assertEquals(t0, keep.since());
        // OK policy on a firing alert resolves it
        assertTrue(step(firing, Outcome.NO_DATA, NoDataPolicy.OK, hold5, t0.plusSeconds(60)).resolve());
    }

    @Test
    void storeErrorIsNeverTreatedAsOkAndNeverResolvesAFiringAlert() {
        Transition fromOk = step(Current.initial(t0), Outcome.ERROR, NoDataPolicy.OK, hold5, t0.plusSeconds(60));
        assertEquals(State.ERROR, fromOk.state());
        assertFalse(fromOk.fire());
        Transition fromFiring = step(new Current(State.FIRING, t0), Outcome.ERROR, NoDataPolicy.OK, hold5, t0.plusSeconds(60));
        assertEquals(State.FIRING, fromFiring.state());
        assertFalse(fromFiring.resolve());
        // staying in ERROR keeps the original since
        Transition again = step(cur(fromOk), Outcome.ERROR, NoDataPolicy.OK, hold5, t0.plusSeconds(120));
        assertEquals(fromOk.since(), again.since());
        // recovery from ERROR to a clean read goes to OK
        assertEquals(State.OK, step(cur(again), Outcome.OK, NoDataPolicy.KEEP, hold5, t0.plusSeconds(180)).state());
    }

    @Test
    void reEvaluatingTheSamePersistedStateAfterARestartDoesNotNotifyTwice() {
        Transition fired = step(Current.initial(t0), Outcome.BREACH, NoDataPolicy.KEEP, Duration.ZERO, t0);
        assertTrue(fired.fire());
        Current persisted = cur(fired); // what the database holds
        for (int i = 1; i <= 3; i++) {
            Transition t = step(persisted, Outcome.BREACH, NoDataPolicy.KEEP, Duration.ZERO, t0.plusSeconds(60L * i));
            assertFalse(t.fire());
            assertFalse(t.resolve());
            persisted = cur(t);
        }
    }
}
