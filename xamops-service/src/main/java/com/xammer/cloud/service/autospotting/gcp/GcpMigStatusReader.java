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
        ObjectNode capacity = JSON.createObjectNode();
        capacity.put("desired", g.targetSize());
        capacity.put("standard", onStandard);
        capacity.put("spot", onSpot);
        capacity.put("ownedFingerprint", GcpFingerprint.of(g));
        boolean foreign = (!g.versionTargetReached() || !g.stable()) && !g.isXamopsPair();
        try {
            return new GroupStatus(true, GcpFingerprint.planHash(g), JSON.writeValueAsString(health),
                    JSON.writeValueAsString(capacity), foreign);
        } catch (JsonProcessingException e) {
            throw new GcpReadException("status", ErrorKind.VALIDATION);
        }
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
