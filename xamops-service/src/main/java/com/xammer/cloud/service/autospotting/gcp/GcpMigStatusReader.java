package com.xammer.cloud.service.autospotting.gcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xammer.cloud.service.autospotting.spi.ErrorKind;
import com.xammer.cloud.service.autospotting.spi.GroupStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Reads a MIG's live status for {@code getStatus} and the reconciler (design-gcp.md §2.2, §4.1). A
 * bounded read set per group: the MIG, its managed instances, its autoscaler (when linked) and the
 * recent {@code listErrors} page. A confirmed 404 is {@code NOT_FOUND} (feeds the GONE check); any
 * other failure is rethrown so a permission problem is never read as a missing group (§9.2).
 *
 * <p>{@code liveFingerprint} is the plan hash ({@link GcpFingerprint#planHash}): the staleness
 * fingerprint of §2.2. The owned fingerprint of §2.9 (drift) rides in {@code capacityJson} as
 * {@code ownedFingerprint}, so the Phase 27 drift check can compare like with like.
 */
@Component
public class GcpMigStatusReader {
    private static final ObjectMapper JSON = new ObjectMapper();

    /** What one read returned. */
    public record Read(GroupStatus status, GcpMigFacts group, JsonNode autoscaler,
                       List<GcpSnapshotCodec.TemplateRead> templates) {
    }

    /** The MIG itself, or empty on a confirmed 404; other failures throw. */
    public java.util.Optional<JsonNode> describeGroup(GcpReadOnlyView view, GcpEligibilityEvaluator.Key key) {
        try {
            return java.util.Optional.of(view.reads().getMig(!key.zonal(), key.location(), key.name()));
        } catch (GcpReadException e) {
            if (e.kind() == ErrorKind.NOT_FOUND) {
                return java.util.Optional.empty();
            }
            throw e;
        }
    }

    public GroupStatus read(GcpReadOnlyView view, String groupKey) {
        GcpEligibilityEvaluator.Key key = GcpEligibilityEvaluator.split(groupKey);
        java.util.Optional<JsonNode> raw = describeGroup(view, key);
        if (raw.isEmpty()) {
            return GroupStatus.notFound();
        }
        GcpMigFacts g = GcpMigFacts.from(key.project(), raw.get());
        List<JsonNode> instances = view.reads().listManagedInstances(g.regional(), g.location(), g.name());
        List<JsonNode> errors = List.of();
        try {
            errors = view.reads().listErrors(g.regional(), g.location(), g.name());
        } catch (GcpReadException e) {
            // listErrors is an addition: the base status stands without it.
        }
        ObjectNode health = JSON.createObjectNode();
        health.put("versionTargetReached", g.versionTargetReached());
        health.put("stable", g.stable());
        health.set("currentActions", g.currentActions().deepCopy());
        int running = 0;
        int healthy = 0;
        int onStandard = 0;
        int onSpot = 0;
        for (JsonNode mi : instances) {
            boolean up = "RUNNING".equals(mi.path("instanceStatus").asText()) && "NONE".equals(mi.path("currentAction").asText("NONE"));
            if (up) {
                running++;
                boolean unhealthy = false;
                for (JsonNode h : mi.path("instanceHealth")) {
                    unhealthy |= "UNHEALTHY".equals(h.path("detailedHealthState").asText());
                }
                if (!unhealthy) {
                    healthy++;
                }
            }
            String version = mi.path("version").path("name").asText("");
            if (GcpMigFacts.SPOT_VERSION.equals(version)) {
                onSpot++;
            } else if (GcpMigFacts.STANDARD_VERSION.equals(version)) {
                onStandard++;
            }
        }
        health.put("running", running);
        health.put("healthyInService", healthy);
        health.put("managedInstances", instances.size());
        int capacityErrors = 0;
        for (JsonNode e : errors) {
            if (GcpErrorMapper.fromOperationCode(e.path("error").path("code").asText(null)) == ErrorKind.CAPACITY) {
                capacityErrors++;
            }
        }
        health.put("recentCapacityErrors", capacityErrors);
        // The signals of design-gcp.md §7.1, raw: the provider adds the windows and the carried since-times.
        int notDeleting = 0;
        int stuck = 0;
        com.fasterxml.jackson.databind.node.ArrayNode stuckNames = health.putArray("creatingInstances");
        for (JsonNode mi : instances) {
            String action = mi.path("currentAction").asText("NONE");
            if ("RUNNING".equals(mi.path("instanceStatus").asText())
                    && !"DELETING".equals(action) && !"ABANDONING".equals(action)) {
                notDeleting++;
            }
            if (("CREATING".equals(action) || "CREATING_WITHOUT_RETRIES".equals(action) || "RECREATING".equals(action))
                    && mi.path("lastAttempt").path("errors").path("errors").size() > 0) {
                stuck++;
                stuckNames.add(shortName(mi.path("instance").asText("")));
            }
        }
        // What the preemption tracker needs to match zone operations to this group (design-gcp.md §8.2); it removes them.
        health.put("baseInstanceName", g.raw().path("baseInstanceName").asText(null));
        com.fasterxml.jackson.databind.node.ArrayNode zones = health.putArray("zones");
        if (g.regional()) {
            g.distributionZones().forEach(zones::add);
        } else if (g.location() != null) {
            zones.add(g.location());
        }
        String spotTemplate = null;
        for (GcpMigFacts.Version v : g.versions()) {
            if (GcpMigFacts.SPOT_VERSION.equals(v.name())) {
                spotTemplate = v.instanceTemplate();
            }
        }
        health.put("spotTemplate", spotTemplate);
        com.fasterxml.jackson.databind.node.ArrayNode seenInstances = health.putArray("instances");
        for (JsonNode mi : instances) {
            if (seenInstances.size() >= 300) {
                break;
            }
            String link = mi.path("instance").asText("");
            String zoneLink = link.contains("/zones/") ? link.substring(link.indexOf("/zones/") + 7) : "";
            ObjectNode o = seenInstances.addObject();
            o.put("n", shortName(link));
            o.put("z", zoneLink.contains("/") ? zoneLink.substring(0, zoneLink.indexOf('/')) : null);
            o.put("s", GcpMigFacts.SPOT_VERSION.equals(mi.path("version").path("name").asText("")));
            o.put("a", mi.path("currentAction").asText("NONE"));
        }
        health.put("runningNotDeleting", notDeleting);
        health.put("stuckCreating", stuck);
        health.put("shortfall", Math.max(0, g.targetSize() - notDeleting));
        com.fasterxml.jackson.databind.node.ArrayNode errorList = health.putArray("errors");
        for (JsonNode e : errors) {
            if (errorList.size() >= 50) {
                break;
            }
            ObjectNode o = errorList.addObject();
            o.put("code", e.path("error").path("code").asText(""));
            o.put("instance", shortName(e.path("instanceActionDetails").path("instance").path("instance").asText(
                    e.path("instanceActionDetails").path("instance").asText(""))));
            o.put("timestamp", e.path("timestamp").asText(""));
            String metric = quotaMetric(e);
            if (metric != null) {
                o.put("quotaMetric", metric);
            }
        }
        ObjectNode capacity = JSON.createObjectNode();
        capacity.put("desired", g.targetSize());
        capacity.put("standard", onStandard);
        capacity.put("spot", onSpot);
        capacity.put("hasSpotVersion", g.versions().stream().anyMatch(v -> GcpMigFacts.SPOT_VERSION.equals(v.name())));
        capacity.put("ownedFingerprint", GcpFingerprint.of(g));
        // A rollout XamOps started carries its xas-* version names (the pair, or the single standard version of a
        // fallback): only an unnamed or customer-named rollout is foreign (DR-6).
        boolean ours = g.versions().stream().anyMatch(v -> GcpMigFacts.STANDARD_VERSION.equals(v.name())
                || GcpMigFacts.SPOT_VERSION.equals(v.name()));
        boolean foreign = (!g.versionTargetReached() || !g.stable()) && !ours;
        try {
            return new GroupStatus(true, GcpFingerprint.planHash(g), JSON.writeValueAsString(health),
                    JSON.writeValueAsString(capacity), foreign);
        } catch (JsonProcessingException e) {
            throw new GcpReadException("status", ErrorKind.VALIDATION);
        }
    }

    private static String shortName(String link) {
        int i = link == null ? -1 : link.lastIndexOf('/');
        return i < 0 ? link : link.substring(i + 1);
    }
    private static final java.util.regex.Pattern QUOTA_IN_TEXT =
            java.util.regex.Pattern.compile("[Qq]uota '?\"?([A-Z][A-Z0-9_]+)'?\"? exceeded");
    /** The quota metric of a {@code QUOTA_EXCEEDED} entry, from the structured detail when present, else the message. */
    static String quotaMetric(JsonNode entry) {
        for (JsonNode err : entry.path("error").path("errors")) {
            for (JsonNode d : err.path("errorDetails")) {
                String m = d.path("quotaInfo").path("metricName").asText(null);
                if (m != null && !m.isBlank()) {
                    return m.substring(m.lastIndexOf('/') + 1).toUpperCase(java.util.Locale.ROOT);
                }
            }
            java.util.regex.Matcher m = QUOTA_IN_TEXT.matcher(err.path("message").asText(""));
            if (m.find()) {
                return m.group(1);
            }
        }
        return null;
    }
    /** Read the MIG, its autoscaler and every referenced template (used by the snapshot and the plan). */
    public Read readForSnapshot(GcpReadOnlyView view, String groupKey) {
        GcpEligibilityEvaluator.Key key = GcpEligibilityEvaluator.split(groupKey);
        JsonNode raw = describeGroup(view, key).orElseThrow(() -> new GcpReadException("instanceGroupManagers.get", ErrorKind.NOT_FOUND));
        GcpMigFacts g = GcpMigFacts.from(key.project(), raw);
        JsonNode autoscaler = g.autoscalerLink() == null ? null : view.reads().getAutoscaler(g.autoscalerLink());
        List<GcpSnapshotCodec.TemplateRead> templates = new ArrayList<>();
        for (String link : g.templateLinks()) {
            templates.add(new GcpSnapshotCodec.TemplateRead(link, view.reads().getInstanceTemplate(link)));
        }
        return new Read(null, g, autoscaler, templates);
    }
}
