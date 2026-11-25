package com.xammer.cloud.xampulse.service.alert;

import java.time.Duration;
import java.time.Instant;

/**
 * Edge-triggered alert state: OK -> PENDING -> FIRING -> RESOLVED. Pure; persisted state in, next state out.
 *
 * <ul>
 *   <li>A notification is requested only on the transition into FIRING ({@code fire}) or out of it ({@code resolve}).
 *       Re-evaluating the same observation (for example after a restart) therefore never notifies twice.</li>
 *   <li>{@code for} holds a breach in PENDING until it has lasted long enough.</li>
 *   <li>Flap guard: a breach within {@link #FLAP_WINDOW} of a resolution re-enters FIRING without a new
 *       notification ({@code quietReopen}).</li>
 *   <li>ERROR (the store could not be read) is its own state and never counts as OK.</li>
 * </ul>
 */
public final class AlertStateMachine {

    public enum State { OK, PENDING, FIRING, RESOLVED, ERROR }

    public enum Outcome { BREACH, OK, NO_DATA, ERROR }

    public enum NoDataPolicy { ALERT, OK, KEEP }

    public record Current(State state, Instant since) {
        public static Current initial(Instant now) {
            return new Current(State.OK, now);
        }
    }

    public record Transition(State state, Instant since, boolean fire, boolean resolve, boolean quietReopen) {}

    public static final Duration FLAP_WINDOW = Duration.ofMinutes(5);

    private AlertStateMachine() {}

    public static Transition step(Current cur, Outcome outcome, NoDataPolicy noData, Duration hold, Instant now) {
        Outcome effective = outcome;
        if (outcome == Outcome.NO_DATA) {
            switch (noData) {
                case ALERT -> effective = Outcome.BREACH;
                case OK -> effective = Outcome.OK;
                case KEEP -> {
                    return new Transition(cur.state(), cur.since(), false, false, false);
                }
            }
        }
        if (effective == Outcome.ERROR) {
            // Unknown is not healthy: do not resolve, do not fire. A FIRING alert stays FIRING.
            if (cur.state() == State.FIRING) {
                return new Transition(State.FIRING, cur.since(), false, false, false);
            }
            return new Transition(State.ERROR, cur.state() == State.ERROR ? cur.since() : now, false, false, false);
        }
        boolean breach = effective == Outcome.BREACH;
        return switch (cur.state()) {
            case OK, ERROR -> breach ? enter(now, hold, false) : new Transition(State.OK, cur.state() == State.OK ? cur.since() : now, false, false, false);
            case RESOLVED -> {
                if (!breach) {
                    yield new Transition(State.OK, now, false, false, false);
                }
                boolean flap = Duration.between(cur.since(), now).compareTo(FLAP_WINDOW) < 0;
                if (flap) {
                    yield new Transition(State.FIRING, now, false, false, true);
                }
                yield enter(now, hold, false);
            }
            case PENDING -> {
                if (!breach) {
                    yield new Transition(State.OK, now, false, false, false);
                }
                if (Duration.between(cur.since(), now).compareTo(hold) >= 0) {
                    yield new Transition(State.FIRING, now, true, false, false);
                }
                yield new Transition(State.PENDING, cur.since(), false, false, false);
            }
            case FIRING -> breach
                    ? new Transition(State.FIRING, cur.since(), false, false, false)
                    : new Transition(State.RESOLVED, now, false, true, false);
        };
    }

    private static Transition enter(Instant now, Duration hold, boolean quiet) {
        if (hold.isZero()) {
            return new Transition(State.FIRING, now, true, false, quiet);
        }
        return new Transition(State.PENDING, now, false, false, false);
    }
}
