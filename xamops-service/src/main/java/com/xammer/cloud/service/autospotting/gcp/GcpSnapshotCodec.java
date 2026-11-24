package com.xammer.cloud.service.autospotting.gcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xammer.cloud.domain.autospotting.Provider;
import com.xammer.cloud.service.autospotting.spi.SnapshotPayload;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the GCP snapshot payload (design-gcp.md §2.2, {@code schema_version=1}): every non-output
 * field needed to restore a MIG exactly, as read. Scope (zonal or regional) and the zone list come
 * from the resource. {@code exact=true} only when every referenced template was read; a discovery
 * snapshot for a legacy-converted MIG is {@code exact=false} because the pre-conversion template is
 * unknown (FR-SPN-12).
 */
public final class GcpSnapshotCodec {
    public static final int SCHEMA_VERSION = 1;
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private GcpSnapshotCodec() {
    }

    /** One referenced template: its self-link and the resource JSON read (null when it was not read). */
    public record TemplateRead(String selfLink, JsonNode resource) {
    }

    /**
     * @param autoscaler       the autoscaler resource read for the MIG, or null when the MIG has none
     * @param templates        the templates referenced by the MIG, in order
     * @param forceInexact     true for a reconstructed snapshot (discovery of a legacy-converted MIG)
     * @param extraReasons     reasons to record as inexact
     */
    public static SnapshotPayload encode(GcpMigFacts g, JsonNode autoscaler, List<TemplateRead> templates,
                                         boolean forceInexact, List<String> extraReasons) {
        ObjectNode n = JSON.createObjectNode();
        n.put("migSelfLink", g.selfLink());
        n.put("migId", g.id());
        n.put("locationType", g.locationType());
        if (g.regional()) {
            n.put("region", g.location());
        } else {
            n.put("zone", g.location());
        }
        n.put("name", g.name());
        n.put("project", g.project());
        n.put("instanceTemplate", g.instanceTemplate());
        n.set("versions", copy(g.raw().path("versions"), JSON.createArrayNode()));
        n.set("updatePolicy", copy(g.updatePolicy(), null));
        n.set("instanceFlexibilityPolicy", copy(g.instanceFlexibilityPolicy(), null));
        n.set("allInstancesConfig", copy(g.allInstancesConfig(), null));
        n.set("autoHealingPolicies", copy(g.autoHealingPolicies(), JSON.createArrayNode()));
        n.set("distributionZones", JSON.valueToTree(g.distributionZones()));
        n.put("targetShape", g.targetShape());
        n.put("targetSize", g.targetSize());
        n.put("autoscalerSelfLink", g.autoscalerLink());
        n.set("autoscalingPolicy", autoscaler == null ? null : copy(autoscaler.path("autoscalingPolicy"), null));
        n.put("fingerprint", g.rawFingerprint());
        n.put("planHash", GcpFingerprint.planHash(g));
        ArrayNode ts = n.putArray("templates");
        List<String> reasons = new ArrayList<>(extraReasons == null ? List.of() : extraReasons);
        boolean allRead = true;
        for (TemplateRead t : templates) {
            ObjectNode e = ts.addObject();
            e.put("selfLink", t.selfLink());
            if (t.resource() == null) {
                allRead = false;
                reasons.add("TEMPLATE_NOT_READ:" + t.selfLink());
                e.putNull("resource");
                continue;
            }
            e.put("id", t.resource().path("id").asText(null));
            e.put("scope", t.resource().hasNonNull("region") ? "REGIONAL" : "GLOBAL");
            e.set("resource", t.resource().deepCopy());
            e.put("sha256", GcpFingerprint.sha256(write(t.resource())));
        }
        boolean exact = allRead && !forceInexact;
        String payload = write(n);
        return new SnapshotPayload(Provider.GCP, SCHEMA_VERSION, exact, exact ? List.of() : List.copyOf(reasons),
                payload, GcpFingerprint.sha256(payload));
    }

    private static JsonNode copy(JsonNode n, JsonNode ifMissing) {
        if (n == null || n.isMissingNode() || n.isNull()) {
            return ifMissing;
        }
        return n.deepCopy();
    }

    static String write(JsonNode n) {
        try {
            return JSON.writeValueAsString(JSON.treeToValue(n, Object.class));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("snapshot serialization failed", e);
        }
    }
}
