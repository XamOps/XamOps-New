package com.xammer.cloud.service.autospotting.gcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xammer.cloud.service.autospotting.spi.ErrorKind;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The success test of a GCP rollout (design-gcp.md §4.4, FR-GCP-05, AC-GCP-04) and the stall rules of §3.6.
 * Pure: it takes one observation and the state parked by the previous poll.
 *
 * <p>Success needs ALL of: {@code status.versionTarget.isReached}; {@code status.isStable} on two consecutive
 * polls at least 30 s apart; every instance on the template its version maps to (nothing outside the target
 * set); at least {@code min(N, targetSize)} instances on the standard version; and a healthy count equal to
 * {@code targetSize}. An accepted PATCH, or an operation that is DONE, is never success.
 *
 * <p>Stalls: no progress for 20 min with capacity errors is a capacity stall, and with unhealthy new instances a
 * health stall; both fail a PROGRESS rollout (the runner compensates) and only wait in {@link Mode#LENIENT}
 * (compensation and SAFETY ops never roll toward Spot). No progress for 45 min without errors is a silent stall:
 * it keeps waiting and flags {@code stalled}. A deadline is the core's STUCK, never FAILED for time.
 */
@Component
public class GcpVersionTargetEvaluator {
    public static final Duration STABLE_GAP = Duration.ofSeconds(30);
    public static final Duration STALL = Duration.ofMinutes(20);
    public static final Duration SILENT_STALL = Duration.ofMinutes(45);
    public static final String CAPACITY_STALL = "GCP_CAPACITY_STALL";
    public static final String HEALTH_STALL = "GCP_HEALTH_STALL";
    private static final ObjectMapper JSON = new ObjectMapper();

    public enum Mode { PROGRESS, LENIENT }

    /** One managed instance as listed. {@code health} is the worst {@code detailedHealthState}, or null. */
    public record Instance(String versionName, String template, String status, String currentAction, String health) {
        static Instance from(JsonNode mi) {
            String health = null;
            for (JsonNode h : mi.path("instanceHealth")) {
                String s = h.path("detailedHealthState").asText(null);
                if (s != null && (health == null || !"HEALTHY".equals(s))) {
                    health = s;
                }
            }
            return new Instance(mi.path("version").path("name").asText(null),
                    mi.path("version").path("instanceTemplate").asText(null), mi.path("instanceStatus").asText(null),
                    mi.path("currentAction").asText("NONE"), health);
        }
    }

    public record Observation(Instant now, boolean versionTargetReached, boolean stable, int targetSize,
                              List<Instance> instances, boolean autoHealing, List<String> errorCodes) {
        public static Observation of(Instant now, GcpMigFacts mig, List<JsonNode> managed, List<JsonNode> errors) {
            return new Observation(now, mig.versionTargetReached(), mig.stable(), mig.targetSize(),
                    managed.stream().map(Instance::from).toList(), mig.hasAutoHealing(),
                    errors.stream().map(e -> e.path("error").path("code").asText("")).toList());
        }
    }

    /** What the rollout must reach: version name to template, and the standard floor. */
    public record Target(Map<String, String> templateByVersion, int floorN) {
    }

    /** The state parked between polls. */
    public record State(Instant startedAt, Instant lastProgressAt, int onTarget, Instant stableSince) {
        public static State fromJson(String json, Instant now) {
            if (json == null || json.isBlank()) {
                return new State(now, now, -1, null);
            }
            try {
                JsonNode n = JSON.readTree(json).path("state");
                if (!n.isObject()) {
                    return new State(now, now, -1, null);
                }
                return new State(instant(n, "startedAt", now), instant(n, "lastProgressAt", now),
                        n.path("onTarget").asInt(-1), n.hasNonNull("stableSince") ? instant(n, "stableSince", now) : null);
            } catch (java.io.IOException e) {
                return new State(now, now, -1, null);
            }
        }

        private static Instant instant(JsonNode n, String f, Instant dflt) {
            try {
                return Instant.parse(n.path(f).asText());
            } catch (RuntimeException e) {
                return dflt;
            }
        }
    }

    public sealed interface Verdict permits Done, Waiting, Failed {
    }

    public record Done(State state) implements Verdict {
    }

    public record Waiting(State state, String message, boolean stalled) implements Verdict {
    }

    public record Failed(ErrorKind kind, String code, State state) implements Verdict {
    }

    public Verdict evaluate(Observation o, Target t, State prior, Mode mode) {
        int onTarget = 0;
        boolean outside = false;
        int standard = 0;
        int healthy = 0;
        boolean unhealthyNew = false;
        for (Instance i : o.instances()) {
            String expected = expectedFor(i, t);
            boolean on = expected != null && sameTemplate(expected, i.template());
            if (!on) {
                outside = true;
            }
            if (on && "NONE".equals(i.currentAction())) {
                onTarget++;
            }
            if (on && GcpMigFacts.STANDARD_VERSION.equals(i.versionName())) {
                standard++;
            }
            boolean running = "RUNNING".equals(i.status());
            boolean ok = running && (!o.autoHealing() || "HEALTHY".equals(i.health()));
            if (ok) {
                healthy++;
            }
            if (on && ("UNHEALTHY".equals(i.health()) || "TIMEOUT".equals(i.health()))) {
                unhealthyNew = true;
            }
        }
        Instant lastProgress = prior.onTarget() < 0 || onTarget > prior.onTarget() ? o.now() : prior.lastProgressAt();
        Instant stableSince = o.stable() ? (prior.stableSince() == null ? o.now() : prior.stableSince()) : null;
        State next = new State(prior.startedAt(), lastProgress, onTarget, stableSince);

        boolean stableTwice = o.stable() && prior.stableSince() != null
                && !o.now().isBefore(prior.stableSince().plus(STABLE_GAP));
        boolean floorHeld = standard >= GcpFloorPlanner.minStandard(t.floorN(), o.targetSize());
        if (o.versionTargetReached() && stableTwice && !outside && floorHeld && healthy == o.targetSize()
                && o.instances().size() == o.targetSize()) {
            return new Done(next);
        }
        Duration idle = Duration.between(lastProgress, o.now());
        boolean capacityErrors = o.errorCodes().stream().anyMatch(GcpVersionTargetEvaluator::capacityCode);
        if (idle.compareTo(STALL) >= 0 && mode == Mode.PROGRESS) {
            if (capacityErrors) {
                return new Failed(ErrorKind.CAPACITY, CAPACITY_STALL, next);
            }
            if (unhealthyNew) {
                return new Failed(ErrorKind.VALIDATION, HEALTH_STALL, next);
            }
        }
        boolean stalled = idle.compareTo(SILENT_STALL) >= 0 && !capacityErrors;
        String message = "versionTarget.isReached=" + o.versionTargetReached() + ", stable=" + o.stable()
                + ", " + onTarget + " of " + o.targetSize() + " on target"
                + (stalled ? ", no progress for " + idle.toMinutes() + " min" : "");
        return new Waiting(next, message, stalled);
    }

    private static String expectedFor(Instance i, Target t) {
        if (i.versionName() != null && t.templateByVersion().containsKey(i.versionName())) {
            return t.templateByVersion().get(i.versionName());
        }
        // A single-version MIG may report no version name: it must run the one target template.
        return i.versionName() == null && t.templateByVersion().size() == 1
                ? t.templateByVersion().values().iterator().next() : null;
    }

    static boolean capacityCode(String code) {
        return code != null && (code.startsWith("ZONE_RESOURCE_POOL_EXHAUSTED") || code.startsWith("QUOTA_EXCEEDED")
                || code.contains("RESOURCE_POOL_EXHAUSTED"));
    }

    static boolean sameTemplate(String a, String b) {
        return GcpTemplateRegistry.sameTemplate(a, b);
    }

    /** The parked progress JSON: the state plus a human line. */
    public static String progressJson(Observation o, Target t, State s, String message) {
        ObjectNode n = JSON.createObjectNode();
        ObjectNode st = n.putObject("state");
        st.put("startedAt", s.startedAt().toString());
        st.put("lastProgressAt", s.lastProgressAt().toString());
        st.put("onTarget", s.onTarget());
        if (s.stableSince() != null) {
            st.put("stableSince", s.stableSince().toString());
        }
        n.put("targetSize", o.targetSize());
        n.put("floor", t.floorN());
        n.put("message", message);
        return n.toString();
    }
}
