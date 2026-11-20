package com.xammer.cloud.service.autospotting.aws;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xammer.cloud.service.autospotting.spi.ErrorKind;

import java.time.Instant;

/**
 * The shared rollout decision (design-aws.md §2.5, plan-phase-18.md task 18.2). {@code WAIT_ROLLOUT}
 * calls it on every 60 s poll, and the reconciler's health pass calls {@link #shortage} for a
 * switch-paused op, so both read the same rules. Pure: no I/O, time is a parameter.
 *
 * <p>Done only when all of: {@code |spotNow − spotTarget| ≤ tolerance} (1 instance, or 10% of desired
 * when larger); {@code spotNow ≥ max(1, spotTarget − tolerance)} whenever {@code spotTarget ≥ 1}
 * (PD-03, DR-07: the tolerance never accepts zero Spot); and {@code healthyInService ≥ desired}.
 *
 * <p>Failure rules, each timed against {@link State} the caller carries between polls:
 * <ul>
 *   <li>{@code healthyInService < desired} for the shortfall window (10 min) with a Spot-capacity
 *       failure in that window: {@code Failed(CAPACITY, SPOT_SHORTFALL_DURING_ROLLOUT)};</li>
 *   <li>the same shortfall with no Spot cause: {@code Failed(VALIDATION, ROLLOUT_UNHEALTHY)};</li>
 *   <li>{@code healthyInService ≥ desired} but Spot not reached, with Spot-capacity failures: keep
 *       waiting up to the spot-wait cap (2 h) after the first such failure, then
 *       {@code Failed(CAPACITY, SPOT_CAPACITY_STALL)}. A FALLBACK_EXIT stalls after 30 min with no
 *       Spot progress instead.</li>
 * </ul>
 */
@org.springframework.stereotype.Component
public class AwsRolloutEvaluator {

    private static final ObjectMapper JSON = new ObjectMapper();

    public static final String SPOT_SHORTFALL = "SPOT_SHORTFALL_DURING_ROLLOUT";
    public static final String SPOT_STALL = "SPOT_CAPACITY_STALL";
    public static final String ROLLOUT_UNHEALTHY = "ROLLOUT_UNHEALTHY";

    /** Which rule set applies. */
    public enum Mode { PROGRESS, FALLBACK_EXIT }

    /** What one poll observed. {@code lastSpotFailureAt} is the latest Spot-capacity failure, or null. */
    public record Observation(Instant now, int desired, int spotNow, int healthyInService, Instant lastSpotFailureAt) {
    }

    /** What the evaluator carries between polls (persisted in the op's progress JSON). */
    public record State(Instant unhealthySince, Instant firstSpotFailureAt, int spotMark, Instant spotMarkAt,
                        int lastSpotNow) {
        public static State initial() {
            return new State(null, null, 0, null, 0);
        }

        /** Read from a progress JSON; any missing or malformed field reads as initial. */
        public static State fromJson(String json) {
            if (json == null || json.isBlank()) {
                return initial();
            }
            try {
                JsonNode n = JSON.readTree(json);
                return new State(instant(n, "unhealthySince"), instant(n, "firstSpotFailureAt"),
                        n.path("spotMark").asInt(0), instant(n, "spotMarkAt"), n.path("lastSpotNow").asInt(0));
            } catch (JsonProcessingException e) {
                return initial();
            }
        }

        private static Instant instant(JsonNode n, String f) {
            String v = n.path(f).asText(null);
            if (v == null) {
                return null;
            }
            try {
                return Instant.parse(v);
            } catch (java.time.format.DateTimeParseException e) {
                return null;
            }
        }

        void into(ObjectNode out) {
            out.put("unhealthySince", unhealthySince == null ? null : unhealthySince.toString());
            out.put("firstSpotFailureAt", firstSpotFailureAt == null ? null : firstSpotFailureAt.toString());
            out.put("spotMark", spotMark);
            out.put("spotMarkAt", spotMarkAt == null ? null : spotMarkAt.toString());
            out.put("lastSpotNow", lastSpotNow);
        }
    }

    /** The evaluator's answer for one poll. */
    public sealed interface Verdict permits Done, Waiting, Failed {
        State state();
    }

    public record Done(State state) implements Verdict {
    }

    /** Keep polling. {@code message} is the plan-progress text. */
    public record Waiting(State state, String message) implements Verdict {
    }

    public record Failed(State state, ErrorKind kind, String code) implements Verdict {
    }

    private final AwsRolloutProperties props;

    public AwsRolloutEvaluator(AwsRolloutProperties props) {
        this.props = props;
    }

    /** The Spot tolerance at {@code desired}: the configured instances, or 10% of desired when larger. */
    public double tolerance(int desired) {
        return Math.max(props.tolerance(), AwsRolloutProperties.TOLERANCE_FRACTION * desired);
    }

    /** The Spot count the profile expects at {@code desired}; none for a group of 1 or fewer. */
    public static int spotTarget(int desired, int base, int percentAboveBase) {
        return desired <= 1 ? 0 : AwsProfiles.spotCount(desired, base, percentAboveBase);
    }

    /** Whether the Spot count has converged to the profile's target (the Done rule without the health term). */
    public boolean spotConverged(int desired, int spotNow, int spotTarget) {
        double tol = tolerance(desired);
        if (Math.abs(spotNow - spotTarget) > tol) {
            return false;
        }
        return spotTarget < 1 || spotNow >= Math.max(1, spotTarget - tol);
    }

    /** Evaluate one forward-leg poll. */
    public Verdict evaluate(Observation o, int spotTarget, State prior, Mode mode) {
        Instant now = o.now();
        Instant window = now.minus(props.shortfallWindow());
        boolean spotCause = o.lastSpotFailureAt() != null && !o.lastSpotFailureAt().isBefore(window);

        if (o.healthyInService() < o.desired()) {
            Instant since = prior.unhealthySince() == null ? now : prior.unhealthySince();
            State next = withProgress(new State(since, prior.firstSpotFailureAt(), prior.spotMark(),
                    prior.spotMarkAt(), prior.lastSpotNow()), o, prior);
            if (!since.plus(props.shortfallWindow()).isAfter(now)) {
                return spotCause
                        ? new Failed(next, ErrorKind.CAPACITY, SPOT_SHORTFALL)
                        : new Failed(next, ErrorKind.VALIDATION, ROLLOUT_UNHEALTHY);
            }
            return new Waiting(next, "Waiting for instances to become healthy");
        }

        Instant firstFailure = prior.firstSpotFailureAt();
        if (o.lastSpotFailureAt() != null && (firstFailure == null || o.lastSpotFailureAt().isBefore(firstFailure))) {
            firstFailure = o.lastSpotFailureAt();
        }
        State next = withProgress(new State(null, firstFailure, prior.spotMark(), prior.spotMarkAt(),
                prior.lastSpotNow()), o, prior);

        if (spotConverged(o.desired(), o.spotNow(), spotTarget)) {
            return new Done(next);
        }
        if (mode == Mode.FALLBACK_EXIT) {
            boolean stalled = o.lastSpotFailureAt() != null && next.spotMarkAt() != null
                    && !next.spotMarkAt().plus(AwsRolloutProperties.FALLBACK_STALL_WINDOW).isAfter(now)
                    && !o.lastSpotFailureAt().isBefore(now.minus(AwsRolloutProperties.FALLBACK_STALL_WINDOW));
            if (stalled) {
                return new Failed(next, ErrorKind.CAPACITY, SPOT_STALL);
            }
        } else if (firstFailure != null && !firstFailure.plus(props.spotWaitCap()).isAfter(now)) {
            return new Failed(next, ErrorKind.CAPACITY, SPOT_STALL);
        }
        if (o.spotNow() > prior.lastSpotNow() && prior.lastSpotNow() >= 0 && prior.spotMarkAt() != null) {
            return new Waiting(next, "Replacing instances with Spot");
        }
        return new Waiting(next, o.lastSpotFailureAt() != null || firstFailure != null
                ? "Waiting for Spot capacity" : "Replacing instances with Spot");
    }

    /**
     * The shortage rows only, for the reconciler's health pass on a switch-paused op (core §4.7):
     * true when the same shortfall rule {@link #evaluate} would fail on (Spot cause or not) holds from
     * {@code unhealthySince}. The reconciler has no poll state of its own, so it passes the time the
     * group has been under desired as observed in the activity feed.
     */
    public boolean shortage(Instant now, int desired, int healthyInService, Instant unhealthySince,
                            Instant lastSpotFailureAt) {
        if (healthyInService >= desired || unhealthySince == null) {
            return false;
        }
        Instant window = now.minus(props.shortfallWindow());
        boolean spotCause = lastSpotFailureAt != null && !lastSpotFailureAt.isBefore(window);
        return spotCause && !unhealthySince.plus(props.shortfallWindow()).isAfter(now);
    }

    /**
     * One compensation-leg poll (PRIOR_CONFIG restore). The prior configuration is the customer's, so
     * no Spot target applies and the leg never fails into another compensation: it is Done once
     * {@code healthyInService ≥ desired} and otherwise keeps waiting.
     */
    public Verdict evaluateCompensation(Observation o, State prior) {
        if (o.healthyInService() >= o.desired()) {
            return new Done(new State(null, prior.firstSpotFailureAt(), prior.spotMark(), prior.spotMarkAt(),
                    o.spotNow()));
        }
        Instant since = prior.unhealthySince() == null ? o.now() : prior.unhealthySince();
        return new Waiting(new State(since, prior.firstSpotFailureAt(), prior.spotMark(), prior.spotMarkAt(),
                o.spotNow()), "Restoring the previous configuration");
    }

    private static State withProgress(State s, Observation o, State prior) {
        int mark = prior.spotMark();
        Instant markAt = prior.spotMarkAt();
        if (markAt == null || o.spotNow() > mark) {
            mark = o.spotNow();
            markAt = o.now();
        }
        return new State(s.unhealthySince(), s.firstSpotFailureAt(), mark, markAt, o.spotNow());
    }

    /** The progress JSON a {@code WAIT_ROLLOUT} poll records ({@code spotNow, spotTarget, inService, desired}). */
    public static String progressJson(Observation o, int spotTarget, State state, String message) {
        ObjectNode n = JSON.createObjectNode();
        n.put("message", message);
        n.put("spotNow", o.spotNow());
        n.put("spotTarget", spotTarget);
        n.put("inService", o.healthyInService());
        n.put("desired", o.desired());
        state.into(n);
        return n.toString();
    }
}
