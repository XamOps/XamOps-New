package com.xammer.cloud.service.autospotting.aws;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.autospotting.ConfigMode;
import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.PauseReason;
import com.xammer.cloud.service.autospotting.core.FallbackProperties;
import com.xammer.cloud.service.autospotting.spi.ReconcileAction;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The pure fallback decisions of {@code AwsSpotProvider.reconcile} (design-aws.md §6.3, plan-phase-20.md task
 * 20.1). No I/O: it reads one status snapshot and the group's lifecycle facts, and the sweep's clock is a
 * parameter.
 *
 * <p>Entry conditions, from the first that applies:
 * <ul>
 *   <li><b>FB-3 quota</b>: a Spot quota failure in the failure window with a shortfall (first observation).</li>
 *   <li><b>FB-2 launch failures</b>: at least 3 Spot-capacity failures in 15 min with a shortfall.</li>
 *   <li><b>FB-1 shortfall</b>: {@code healthyInService < desired} for at least 10 min, with a Spot-capacity
 *       failure or an interruption in that window.</li>
 *   <li><b>FB-4 storm</b>: interruption warnings reach max(3, 30% of the Spot instances) in 30 min. Not
 *       evaluated for APPLYING groups, because a rollout's own replacements make the count unreliable.</li>
 * </ul>
 * The group must be ACTIVE, DEGRADED with {@code config_mode=MANAGED}, PAUSED by the account switch or an
 * operator from one of those, or APPLYING (the core then lets fallback preempt only a CONFIG_APPLY,
 * PROFILE_CHANGE or FALLBACK_EXIT past its first mutation; ENABLE and ADOPT are never preempted).
 * An On-Demand failure never makes a fallback: it only counts toward DEGRADED (AC-EVT-03). A group with no
 * fallback path (already 100% On-Demand above base) becomes DEGRADED with an alert.
 *
 * <p>Health failures without a Spot cause give {@code MARK_DEGRADED} plus an alert. When the failures sit on
 * one instance type ({@code >= 2} unhealthy there and none on another), {@link Decision#proposeRemoveType}
 * names it; the proposal is never applied automatically.
 */
@Component
public class AwsFallbackRules {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final FallbackProperties props;

    public AwsFallbackRules(FallbackProperties props) {
        this.props = props;
    }

    public FallbackProperties properties() {
        return props;
    }

    /** One status snapshot, read from the health and capacity JSON the status reader and provider wrote. */
    public record Signals(Instant now, int desired, int inService, int healthyInService, long failedActivities,
                          Instant shortfallSince, List<Instant> spotFailureTimes, Instant quotaFailureAt,
                          Instant onDemandFailureAt, int interruptions30m, int spotInstances, boolean mixed,
                          Integer odPercentAboveBase, Map<String, Integer> unhealthyByType, Integer placementScore) {
        public boolean shortfall() {
            return healthyInService < desired;
        }
    }

    /** What the rules decided: the actions, the fallback code that fired (or null), and a type to propose removing. */
    public record Decision(List<ReconcileAction> actions, String code, String proposeRemoveType) {
        static Decision none() {
            return new Decision(List.of(), null, null);
        }
    }

    /** Read the snapshot; null when the health JSON is not usable. */
    public static Signals signals(Instant now, String healthJson, String capacityJson) {
        if (healthJson == null) {
            return null;
        }
        try {
            JsonNode h = JSON.readTree(healthJson);
            JsonNode c = capacityJson == null ? JSON.createObjectNode() : JSON.readTree(capacityJson);
            List<Instant> failures = new ArrayList<>();
            h.path("spotFailureTimes").forEach(n -> {
                Instant t = instant(n.asText(null));
                if (t != null) {
                    failures.add(t);
                }
            });
            Map<String, Integer> unhealthy = new TreeMap<>();
            h.path("unhealthyByType").fields().forEachRemaining(e -> unhealthy.put(e.getKey(), e.getValue().asInt()));
            int inService = h.path("inService").asInt(0);
            int desired = c.path("desired").asInt(0);
            return new Signals(now, desired, inService,
                    h.hasNonNull("healthyInService") ? h.path("healthyInService").asInt(0) : inService,
                    h.path("recentFailedActivities").asLong(0), instant(h.path("shortfallSince").asText(null)),
                    failures, instant(h.path("quotaFailureAt").asText(null)),
                    instant(h.path("onDemandFailureAt").asText(null)), h.path("interruptions30m").asInt(0),
                    c.path("spot").asInt(0), c.path("mixed").asBoolean(true),
                    c.hasNonNull("odPercentAboveBase") ? c.path("odPercentAboveBase").asInt() : null, unhealthy,
                    h.hasNonNull("placementScore") ? h.path("placementScore").asInt() : null);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /** The decision for one group. {@code state}, {@code pauseReason}, {@code pausedFrom}, {@code mode} and {@code stateSince} are the row's. */
    public Decision evaluate(GroupState state, PauseReason pauseReason, GroupState pausedFrom, ConfigMode mode,
                             Instant stateSince, Signals s) {
        if (s == null) {
            return Decision.none();
        }
        String code = entryCode(state, s);
        if (code != null && entryState(state, pauseReason, pausedFrom, mode)) {
            if (!hasFallbackPath(s)) {
                // Already 100% On-Demand above base: there is nothing to fall back to (FR-HLT-04).
                return state == GroupState.ACTIVE
                        ? new Decision(List.of(ReconcileAction.MARK_DEGRADED, ReconcileAction.ALERT), "NO_PATH", null)
                        : new Decision(List.of(ReconcileAction.ALERT), "NO_PATH", null);
            }
            return new Decision(List.of(ReconcileAction.ENQUEUE_FALLBACK), code, null);
        }
        if (state == GroupState.FALLBACK && mode == ConfigMode.FALLBACK) {
            return fallbackHealth(stateSince, s);
        }
        return health(state, s);
    }

    // ---- entry ----

    private String entryCode(GroupState state, Signals s) {
        if (!s.shortfall() && !(state != GroupState.APPLYING && storm(s))) {
            return null;
        }
        Duration failureWindow = props.failureWindow();
        Instant windowStart = s.now().minus(failureWindow);
        if (s.shortfall() && s.quotaFailureAt() != null && !s.quotaFailureAt().isBefore(windowStart)) {
            return "FB-3";
        }
        long recent = s.spotFailureTimes().stream().filter(t -> !t.isBefore(windowStart)).count();
        if (s.shortfall() && recent >= props.launchFailures()) {
            return "FB-2";
        }
        if (s.shortfall() && s.shortfallSince() != null
                && !s.shortfallSince().plus(props.shortfall()).isAfter(s.now())) {
            boolean spotCause = s.spotFailureTimes().stream().anyMatch(t -> !t.isBefore(s.shortfallSince()))
                    || s.interruptions30m() > 0;
            if (spotCause) {
                return "FB-1";
            }
        }
        if (state != GroupState.APPLYING && storm(s)) {
            return "FB-4";
        }
        return null;
    }

    private boolean storm(Signals s) {
        return storm(s.interruptions30m(), s.spotInstances());
    }

    /** FB-4: interruption warnings reach max(3, 30% of the Spot instances) in the storm window. */
    public boolean storm(int interruptions30m, int spotInstances) {
        int threshold = Math.max(props.stormMin(), (int) Math.ceil(props.stormFraction() * spotInstances));
        return interruptions30m >= threshold;
    }

    private static boolean hasFallbackPath(Signals s) {
        return s.mixed() && (s.odPercentAboveBase() == null || s.odPercentAboveBase() < 100);
    }

    static boolean entryState(GroupState state, PauseReason pauseReason, GroupState pausedFrom, ConfigMode mode) {
        return switch (state) {
            case ACTIVE -> true;
            case DEGRADED -> mode == ConfigMode.MANAGED;
            case APPLYING -> true;
            case PAUSED -> (pauseReason == PauseReason.ACCOUNT_SWITCH || pauseReason == PauseReason.OPERATOR)
                    && mode == ConfigMode.MANAGED
                    && (pausedFrom == GroupState.ACTIVE || pausedFrom == GroupState.DEGRADED);
            default -> false;
        };
    }

    // ---- health without a fallback ----

    private Decision health(GroupState state, Signals s) {
        boolean spotCause = s.spotFailureTimes().stream()
                .anyMatch(t -> !t.isBefore(s.now().minus(props.failureWindow())));
        boolean unhealthy = (s.failedActivities() > 0 && s.inService() < s.desired())
                || (!s.unhealthyByType().isEmpty() && s.shortfall());
        if (unhealthy && (state == GroupState.ACTIVE || state == GroupState.FALLBACK)) {
            // On-Demand failures and health-check failures end here, never in a fallback (AC-EVT-03, FR-HLT-04).
            List<ReconcileAction> actions = new ArrayList<>(List.of(ReconcileAction.MARK_DEGRADED));
            String propose = null;
            if (!spotCause) {
                actions.add(ReconcileAction.ALERT);
                propose = concentratedType(s);
            }
            return new Decision(actions, null, propose);
        }
        if (state == GroupState.DEGRADED && recovered(s, spotCause)) {
            return new Decision(List.of(ReconcileAction.MARK_RECOVERED), null, null);
        }
        return Decision.none();
    }

    private boolean recovered(Signals s, boolean recentSpotFailure) {
        if (!unhealthyNow(s) && s.inService() >= s.desired() && s.failedActivities() == 0) {
            return true;
        }
        boolean recentOdFailure = s.onDemandFailureAt() != null
                && !s.onDemandFailureAt().isBefore(s.now().minus(props.failureWindow()));
        return !s.shortfall() && s.unhealthyByType().isEmpty() && !recentSpotFailure && !recentOdFailure;
    }

    private static boolean unhealthyNow(Signals s) {
        return !s.unhealthyByType().isEmpty() && s.shortfall();
    }

    /** The one type that holds at least two unhealthy instances while no other type has any, else null. */
    private static String concentratedType(Signals s) {
        if (s.unhealthyByType().size() != 1) {
            return null;
        }
        Map.Entry<String, Integer> only = s.unhealthyByType().entrySet().iterator().next();
        return only.getValue() >= 2 ? only.getKey() : null;
    }

    /** A FALLBACK group: DEGRADED when On-Demand is still short 20 min after the transition, and the exit signal. */
    private Decision fallbackHealth(Instant stateSince, Signals s) {
        if (s.shortfall() && stateSince != null && !stateSince.plus(props.stillShort()).isAfter(s.now())) {
            return new Decision(List.of(ReconcileAction.MARK_DEGRADED, ReconcileAction.ALERT), null, null);
        }
        boolean recentSpotFailure = s.spotFailureTimes().stream()
                .anyMatch(t -> !t.isBefore(s.now().minus(props.failureWindow())));
        boolean capacityBack = !s.shortfall() && !recentSpotFailure;
        boolean scoreOk = s.placementScore() == null || s.placementScore() >= props.minScore();
        if (capacityBack && scoreOk) {
            return new Decision(List.of(ReconcileAction.ENQUEUE_FALLBACK_EXIT), "EXIT", null);
        }
        return Decision.none();
    }

    private static Instant instant(String v) {
        if (v == null || v.isBlank() || "null".equals(v)) {
            return null;
        }
        try {
            return Instant.parse(v);
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }
}
