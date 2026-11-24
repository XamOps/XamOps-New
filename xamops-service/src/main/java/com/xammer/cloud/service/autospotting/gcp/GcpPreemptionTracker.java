package com.xammer.cloud.service.autospotting.gcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.autospotting.Provider;
import com.xammer.cloud.domain.autospotting.SpotGroup;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.autospotting.obs.AutoSpottingMetrics;
import com.xammer.cloud.service.autospotting.spi.GroupStatus;
import com.xammer.cloud.service.autospotting.spi.PreemptionTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Preemption tracking through the reconciler (design-gcp.md §8, D-09, FR-HLT-06). GCP has no push channel in
 * XamOps's setup, so each sweep reads, in order of preference (§8.2):
 * <ol>
 *   <li>zone operations of type {@code compute.instances.preempted} since the per-zone cursor, matched to the group by
 *       the {@code baseInstanceName} prefix or an instance name seen in the last two sweeps (a preempted instance is
 *       deleted, so a live lookup alone would miss it);</li>
 *   <li>an instance that vanished between sweeps with no matching operation: a {@code SUSPECTED_PREEMPTION} row, a
 *       health signal that is never counted as a preemption.</li>
 * </ol>
 * Audit-log backfill (source 2) and Cloud Monitoring (source 3) are not implemented: a cursor older than the gap
 * restarts from {@code now - gap} through zone operations. Each confirmed preemption is a {@code spot_group_event} row
 * ({@code provider=GCP}, {@code event_key}= the operation id, write-time dedupe) and counts once on
 * {@code autospotting.interruptions{source=gcp_preemption}}. Cursors, the names of the last two sweeps and the derived
 * {@code preemptions_1h}, {@code preemptions_24h}, {@code preemption_rate_24h} and per-zone counts live in
 * {@code spot_group.health_state.preemption}. Preemption alone never starts a fallback (§8.4). Read only.
 */
@Component
public class GcpPreemptionTracker implements PreemptionTracker {
    private static final Logger log = LoggerFactory.getLogger(GcpPreemptionTracker.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    static final String OPERATION_TYPE = "compute.instances.preempted";
    private static final int MAX_NAMES = 500;

    private final CloudAccountRepository accounts;
    private final GcpViewSource views;
    private final GcpEventRecorder events;
    private final AutoSpottingMetrics metrics;
    private final GcpHealthProperties health;
    private final Clock clock;

    @Autowired
    public GcpPreemptionTracker(CloudAccountRepository accounts, GcpViewSource views, GcpEventRecorder events,
                                AutoSpottingMetrics metrics, GcpHealthProperties health) {
        this(accounts, views, events, metrics, health, Clock.systemUTC());
    }

    GcpPreemptionTracker(CloudAccountRepository accounts, GcpViewSource views, GcpEventRecorder events,
                         AutoSpottingMetrics metrics, GcpHealthProperties health, Clock clock) {
        this.accounts = accounts;
        this.views = views;
        this.events = events;
        this.metrics = metrics;
        this.health = health;
        this.clock = clock;
    }

    @Override
    public Provider provider() {
        return Provider.GCP;
    }

    @Override
    public GroupStatus track(SpotGroup group, GroupStatus status) {
        if (status == null || !status.found() || status.healthJson() == null || group.getCloudAccountId() == null) {
            return status;
        }
        Optional<CloudAccount> account = accounts.findById(group.getCloudAccountId());
        if (account.isEmpty()) {
            return status;
        }
        try {
            Instant now = clock.instant();
            ObjectNode healthJson = (ObjectNode) JSON.readTree(status.healthJson());
            JsonNode capacity = status.capacityJson() == null ? JSON.createObjectNode() : JSON.readTree(status.capacityJson());
            JsonNode prior = group.getHealthState() == null ? JSON.createObjectNode()
                    : JSON.readTree(group.getHealthState()).path("preemption");
            ObjectNode state = (ObjectNode) JSON.readTree(prior.isObject() ? prior.toString() : "{}");
            GcpReadOnlyView view = views.view(account.get());
            poll(group, healthJson, state, view, now);
            derive(group, capacity, state, now);
            healthJson.remove("instances"); // consumed: it is not state
            healthJson.remove("zones");
            healthJson.remove("baseInstanceName");
            healthJson.set("preemption", state);
            return new GroupStatus(true, status.liveFingerprint(), JSON.writeValueAsString(healthJson),
                    status.capacityJson(), status.foreignActivity());
        } catch (JsonProcessingException e) {
            return status;
        } catch (GcpReadException e) {
            log.debug("preemption poll for group {} failed: {}", group.getId(), e.check());
            return status; // the cursor does not move, so nothing is lost: the next sweep reads the same window
        }
    }

    private void poll(SpotGroup group, ObjectNode healthJson, ObjectNode state, GcpReadOnlyView view, Instant now) {
        String base = healthJson.path("baseInstanceName").asText(null);
        Map<String, String> current = new LinkedHashMap<>(); // instance name -> zone, this sweep
        Map<String, Boolean> onSpot = new LinkedHashMap<>();
        for (JsonNode i : healthJson.path("instances")) {
            current.put(i.path("n").asText(), i.path("z").asText(null));
            onSpot.put(i.path("n").asText(), i.path("s").asBoolean(false));
        }
        // The names of the last two sweeps: this one and the one before it.
        Map<String, String> known = new LinkedHashMap<>();
        state.path("seen").fields().forEachRemaining(e -> known.put(e.getKey(), e.getValue().asText(null)));
        state.path("seenPrev").fields().forEachRemaining(e -> known.putIfAbsent(e.getKey(), e.getValue().asText(null)));
        known.putAll(current);
        String machineType = machineType(state, healthJson.path("spotTemplate").asText(null), view);
        ObjectNode cursors = state.has("cursor") && state.get("cursor").isObject()
                ? (ObjectNode) state.get("cursor") : state.putObject("cursor");
        Instant floor = now.minus(health.preemptionGap());
        List<String> zones = new ArrayList<>();
        healthJson.path("zones").forEach(z -> zones.add(z.asText()));
        Map<String, Boolean> matched = new LinkedHashMap<>();
        for (String zone : zones) {
            Instant cursor = parse(cursors.path(zone).asText(null));
            if (cursor == null || cursor.isBefore(floor)) {
                cursor = cursor == null ? now.minus(Duration.ofHours(1)) : floor;
            }
            Instant newest = cursor;
            for (JsonNode op : view.reads().listZoneOperations(zone, OPERATION_TYPE, cursor.toString())) {
                String instance = lastSegment(op.path("targetLink").asText(""));
                Instant at = parse(op.path("insertTime").asText(null));
                String id = op.path("id").asText(null);
                if (instance == null || at == null || id == null) {
                    continue;
                }
                boolean ours = (base != null && instance.startsWith(base + "-")) || known.containsKey(instance);
                if (!ours) {
                    continue;
                }
                matched.put(instance, true);
                if (events.record(group.getId(), GcpEventRecorder.PREEMPTION, id, instance, machineType, zone, at, now)) {
                    metrics.interruption(Provider.GCP, regionOf(zone), "gcp_preemption");
                }
                if (at.isAfter(newest)) {
                    newest = at;
                }
            }
            cursors.put(zone, newest.toString());
        }
        // Inference, last resort: a Spot instance that vanished with no operation behind it is a health signal only.
        Map<String, String> before = new LinkedHashMap<>();
        state.path("seen").fields().forEachRemaining(e -> before.put(e.getKey(), e.getValue().asText(null)));
        if (!before.isEmpty() && !current.isEmpty()) {
            int recreating = 0;
            for (JsonNode i : healthJson.path("instances")) {
                String a = i.path("a").asText("NONE");
                if ("CREATING".equals(a) || "CREATING_WITHOUT_RETRIES".equals(a) || "RECREATING".equals(a)) {
                    recreating++;
                }
            }
            if (recreating > 0) {
                String sweep = state.path("lastSweepAt").asText(now.toString());
                for (Map.Entry<String, String> e : before.entrySet()) {
                    if (!current.containsKey(e.getKey()) && !matched.containsKey(e.getKey())) {
                        events.record(group.getId(), GcpEventRecorder.SUSPECTED, "susp:" + e.getKey() + ":" + sweep,
                                e.getKey(), machineType, e.getValue(), now, now);
                    }
                }
            }
        }
        ObjectNode prev = state.putObject("seenPrev");
        state.path("seen").fields().forEachRemaining(e -> prev.set(e.getKey(), e.getValue()));
        trim(prev);
        ObjectNode seen = state.putObject("seen");
        current.forEach(seen::put);
        trim(seen);
        state.put("lastSweepAt", now.toString());
    }

    /** The machine type of the Spot template, read once per template change and kept in the state. */
    private String machineType(ObjectNode state, String templateLink, GcpReadOnlyView view) {
        if (templateLink == null) {
            return state.path("machineType").asText(null);
        }
        if (templateLink.equals(state.path("machineTypeTemplate").asText(null)) && state.hasNonNull("machineType")) {
            return state.path("machineType").asText();
        }
        try {
            String type = view.reads().getInstanceTemplate(templateLink).path("properties").path("machineType").asText(null);
            String shortName = type == null ? null : GcpMachineShapes.shortName(type);
            state.put("machineTypeTemplate", templateLink);
            state.put("machineType", shortName);
            return shortName;
        } catch (GcpReadException e) {
            return state.path("machineType").asText(null);
        }
    }

    /** {@code preemptions_1h}, {@code preemptions_24h}, {@code preemption_rate_24h}, per-zone counts and the high-rate clock. */
    private void derive(SpotGroup group, JsonNode capacity, ObjectNode state, Instant now) {
        int h1 = events.countSince(group.getId(), GcpEventRecorder.PREEMPTION, now.minus(Duration.ofHours(1)));
        int h24 = events.countSince(group.getId(), GcpEventRecorder.PREEMPTION, now.minus(Duration.ofHours(24)));
        int spot = Math.max(1, capacity.path("spot").asInt(0));
        double rate = Math.round(h24 * 100.0 / spot) / 100.0;
        state.put("preemptions1h", h1);
        state.put("preemptions24h", h24);
        state.put("rate24h", rate);
        ObjectNode byZone = state.putObject("byZone");
        events.countByZoneSince(group.getId(), now.minus(Duration.ofHours(24))).forEach(byZone::put);
        if (rate > 1.0) {
            Instant since = parse(state.path("highRateSince").asText(null));
            state.put("highRateSince", (since == null ? now : since).toString());
        } else {
            state.putNull("highRateSince");
        }
    }

    private static void trim(ObjectNode names) {
        if (names.size() <= MAX_NAMES) {
            return;
        }
        Iterator<String> it = names.fieldNames();
        List<String> drop = new ArrayList<>();
        int extra = names.size() - MAX_NAMES;
        while (it.hasNext() && drop.size() < extra) {
            drop.add(it.next());
        }
        drop.forEach(names::remove);
    }

    private static Instant parse(String v) {
        if (v == null || v.isBlank() || "null".equals(v)) {
            return null;
        }
        try {
            return java.time.OffsetDateTime.parse(v).toInstant();
        } catch (java.time.format.DateTimeParseException e) {
            try {
                return Instant.parse(v);
            } catch (java.time.format.DateTimeParseException e2) {
                return null;
            }
        }
    }

    private static String lastSegment(String link) {
        if (link == null || link.isBlank()) {
            return null;
        }
        int i = link.lastIndexOf('/');
        return i < 0 ? link : link.substring(i + 1);
    }

    private static String regionOf(String zone) {
        int i = zone == null ? -1 : zone.lastIndexOf('-');
        return i < 0 ? zone : zone.substring(0, i);
    }
}
