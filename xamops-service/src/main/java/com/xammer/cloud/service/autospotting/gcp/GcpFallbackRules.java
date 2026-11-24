package com.xammer.cloud.service.autospotting.gcp;

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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The pure GCP fallback and health decisions of {@code GcpMigSpotProvider.reconcile} (design-gcp.md §7.1, §7.2,
 * DC-07, DC-12). No I/O: one status snapshot, the group's lifecycle facts and a clock.
 *
 * <ul>
 *   <li><b>Enter FALLBACK</b>: {@code shortfall >= 1} for the shortfall window (15 min, three sweeps) and
 *       ({@code capacityErrors >= 3} or {@code quotaErrors >= 1} on preemptible quota). <b>Fast</b>:
 *       shortfall of at least 25% of the target for 10 min with any capacity or quota error.</li>
 *   <li><b>DEGRADED</b>: a shortfall for the window with no capacity or quota cause; fallback impossible because
 *       standard quota is exhausted too ({@code GCP_QUOTA}); and, from FALLBACK, a shortfall still present 20 min
 *       after the transition.</li>
 *   <li><b>Exit FALLBACK</b>: in FALLBACK past the dwell, {@code shortfall = 0} for 30 min. The core applies the
 *       backoff and the daily cap ({@code FallbackEnqueuer}).</li>
 * </ul>
 * Preemption alone never starts a fallback (design-gcp.md §8.4): only a shortfall does.
 */
@Component
public class GcpFallbackRules {
    private static final ObjectMapper JSON = new ObjectMapper();
    public static final String QUOTA = "GCP_QUOTA";
    /** A preemption rate above 1.0 per Spot VM per day for this long raises a WARN (design-gcp.md §8.4). */
    static final Duration PREEMPTION_WARN = Duration.ofDays(3);

    private final GcpHealthProperties health;
    private final FallbackProperties fallback;

    public GcpFallbackRules(GcpHealthProperties health, FallbackProperties fallback) {
        this.health = health;
        this.fallback = fallback.forProvider(com.xammer.cloud.domain.autospotting.Provider.GCP);
    }

    public GcpHealthProperties health() {
        return health;
    }

    public FallbackProperties fallback() {
        return fallback;
    }

    /** What the listErrors page says, deduped by (instance, timestamp) and windowed. */
    public record ErrorCounts(int capacity, int preemptibleQuota, int standardQuota, String standardQuotaMetric,
                              String preemptibleQuotaMetric) {
    }

    /** One status snapshot read from the health and capacity JSON the status reader and the provider wrote. */
    public record Signals(Instant now, int targetSize, int shortfall, Instant shortfallSince, Instant fastSince,
                          Instant noShortfallSince, int capacityErrors, int quotaErrors, int standardQuotaErrors,
                          String standardQuotaMetric, boolean hasSpotVersion, Instant preemptionHighSince) {
        public boolean capacityCause() {
            return capacityErrors > 0 || quotaErrors > 0;
        }
    }

    /** What the rules decided: the actions and the code that fired (or null). */
    public record Decision(List<ReconcileAction> actions, String code) {
        static Decision none() {
            return new Decision(List.of(), null);
        }
    }

    /** Count the capacity and quota errors of the window, deduped by (instance, timestamp); an entry with neither is its own. */
    public ErrorCounts count(JsonNode errors, Instant now) {
        Set<String> seen = new HashSet<>();
        int capacity = 0;
        int preemptible = 0;
        int standard = 0;
        String standardMetric = null;
        String preemptibleMetric = null;
        Instant from = now.minus(health.errorWindow());
        int idx = 0;
        for (JsonNode e : errors) {
            idx++;
            String code = e.path("code").asText("");
            boolean cap = GcpVersionTargetEvaluator.capacityCode(code) && !code.startsWith("QUOTA_EXCEEDED");
            boolean quota = code.startsWith("QUOTA_EXCEEDED");
            if (!cap && !quota) {
                continue;
            }
            String ts = e.path("timestamp").asText("");
            if (!ts.isBlank()) {
                try {
                    if (Instant.parse(ts).isBefore(from)) {
                        continue;
                    }
                } catch (java.time.format.DateTimeParseException ignored) {
                    // an unreadable timestamp counts as current
                }
            }
            String instance = e.path("instance").asText("");
            String key = code + "|" + instance + "|" + ts + (instance.isBlank() && ts.isBlank() ? "|" + idx : "");
            if (!seen.add(key)) {
                continue;
            }
            if (cap) {
                capacity++;
            } else {
                String metric = e.path("quotaMetric").asText(null);
                boolean spotQuota = metric == null || metric.contains("PREEMPTIBLE") || metric.contains("SPOT");
                if (spotQuota) {
                    preemptible++;
                    preemptibleMetric = preemptibleMetric == null ? metric : preemptibleMetric;
                } else {
                    standard++;
                    standardMetric = standardMetric == null ? metric : standardMetric;
                }
            }
        }
        return new ErrorCounts(capacity, preemptible, standard, standardMetric, preemptibleMetric);
    }

    /** Read the snapshot; null when the health JSON is not usable. */
    public static Signals signals(Instant now, String healthJson, String capacityJson) {
        if (healthJson == null) {
            return null;
        }
        try {
            JsonNode h = JSON.readTree(healthJson);
            JsonNode c = capacityJson == null ? JSON.createObjectNode() : JSON.readTree(capacityJson);
            return new Signals(now, c.path("desired").asInt(0), h.path("shortfall").asInt(0),
                    instant(h.path("shortfallSince").asText(null)), instant(h.path("fastShortfallSince").asText(null)),
                    instant(h.path("noShortfallSince").asText(null)), h.path("capacityErrors15m").asInt(0),
                    h.path("quotaErrors15m").asInt(0), h.path("standardQuotaErrors15m").asInt(0),
                    h.path("standardQuotaMetric").asText(null), c.path("hasSpotVersion").asBoolean(false),
                    instant(h.path("preemption").path("highRateSince").asText(null)));
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /** The decision for one group. {@code state}, {@code pauseReason}, {@code pausedFrom}, {@code mode}, {@code stateSince} are the row's. */
    public Decision evaluate(GroupState state, PauseReason pauseReason, GroupState pausedFrom, ConfigMode mode,
                             Instant stateSince, Signals s) {
        Decision base = decide(state, pauseReason, pausedFrom, mode, stateSince, s);
        // design-gcp.md §8.4: a preemption rate above 1.0 for 3 days is a WARN (never a fallback on its own).
        if (s != null && s.preemptionHighSince() != null
                && !s.preemptionHighSince().plus(PREEMPTION_WARN).isAfter(s.now())
                && !base.actions().contains(ReconcileAction.ALERT)) {
            List<ReconcileAction> actions = new ArrayList<>(base.actions());
            actions.add(ReconcileAction.ALERT);
            return new Decision(actions, base.code() == null ? "PREEMPTION_RATE_HIGH" : base.code());
        }
        return base;
    }

    private Decision decide(GroupState state, PauseReason pauseReason, GroupState pausedFrom, ConfigMode mode,
                            Instant stateSince, Signals s) {
        if (s == null) {
            return Decision.none();
        }
        if (entryState(state, pauseReason, pausedFrom, mode) && s.hasSpotVersion() && s.shortfall() >= 1) {
            String code = entryCode(s);
            if (code != null) {
                if (s.standardQuotaErrors() > 0) {
                    // Standard quota is exhausted too: falling back cannot help (design-gcp.md §7.3 Quota).
                    return state == GroupState.ACTIVE
                            ? new Decision(List.of(ReconcileAction.MARK_DEGRADED, ReconcileAction.ALERT), QUOTA)
                            : new Decision(List.of(ReconcileAction.ALERT), QUOTA);
                }
                return new Decision(List.of(ReconcileAction.ENQUEUE_FALLBACK), code);
            }
        }
        if (state == GroupState.FALLBACK && mode == ConfigMode.FALLBACK) {
            return fallbackHealth(stateSince, s);
        }
        return healthOnly(state, s);
    }

    private String entryCode(Signals s) {
        boolean sustained = s.shortfallSince() != null && !s.shortfallSince().plus(health.shortfallWindow()).isAfter(s.now());
        if (sustained && (s.capacityErrors() >= health.capacityErrors() || s.quotaErrors() >= health.quotaErrors())) {
            return "GCP-FB-1";
        }
        boolean big = s.targetSize() > 0 && (long) s.shortfall() * 100 >= (long) health.fastPercent() * s.targetSize();
        boolean fast = big && s.fastSince() != null && !s.fastSince().plus(health.fastWindow()).isAfter(s.now());
        if (fast && s.capacityCause()) {
            return "GCP-FB-2";
        }
        return null;
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

    /** ACTIVE: a shortfall with no capacity or quota cause becomes DEGRADED; DEGRADED recovers when nothing is short. */
    private Decision healthOnly(GroupState state, Signals s) {
        boolean sustained = s.shortfall() >= 1 && s.shortfallSince() != null
                && !s.shortfallSince().plus(health.shortfallWindow()).isAfter(s.now());
        if (sustained && !s.capacityCause() && state == GroupState.ACTIVE) {
            return new Decision(List.of(ReconcileAction.MARK_DEGRADED, ReconcileAction.ALERT), null);
        }
        if (state == GroupState.DEGRADED && s.shortfall() == 0) {
            return new Decision(List.of(ReconcileAction.MARK_RECOVERED), null);
        }
        return Decision.none();
    }

    /** A FALLBACK group: DEGRADED when still short 20 min after the transition, the exit signal when quiet, else nothing. */
    private Decision fallbackHealth(Instant stateSince, Signals s) {
        if (s.shortfall() >= 1 && stateSince != null && !stateSince.plus(fallback.stillShort()).isAfter(s.now())) {
            List<ReconcileAction> actions = new ArrayList<>(List.of(ReconcileAction.MARK_DEGRADED, ReconcileAction.ALERT));
            return new Decision(actions, s.standardQuotaErrors() > 0 ? QUOTA : null);
        }
        boolean dwell = stateSince != null && !stateSince.plus(fallback.dwell()).isAfter(s.now());
        Duration quiet = health.exitQuiet();
        boolean quietLongEnough = s.shortfall() == 0 && s.noShortfallSince() != null
                && !s.noShortfallSince().plus(quiet).isAfter(s.now());
        if (dwell && quietLongEnough) {
            return new Decision(List.of(ReconcileAction.ENQUEUE_FALLBACK_EXIT), "EXIT");
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
