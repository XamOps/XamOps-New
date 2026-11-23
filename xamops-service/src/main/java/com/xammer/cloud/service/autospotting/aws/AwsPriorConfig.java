package com.xammer.cloud.service.autospotting.aws;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Optional;

/**
 * Resolves the configuration a PRIOR_CONFIG compensation restores (design-aws.md §2.3 table), in the
 * target-node shape ({@code shape}, {@code launchTemplate}, {@code overrides},
 * {@code instancesDistribution}, {@code capacityRebalance}). Sources in order: the op's
 * {@code compensation_config} (the owned fields at plan time); for ADOPT, the reconstructed snapshot's
 * owned block; for CONFIG_APPLY and PROFILE_CHANGE, the group's {@code desired_config}, which is the
 * configuration the group was converted to before this change. Empty when none exists.
 */
public final class AwsPriorConfig {

    private static final ObjectMapper JSON = new ObjectMapper();

    private AwsPriorConfig() {
    }

    public static Optional<JsonNode> resolve(String compensationConfigJson, String snapshotPayloadJson,
                                             String desiredConfigJson) {
        Optional<JsonNode> a = usable(parse(compensationConfigJson));
        if (a.isPresent()) {
            return a;
        }
        Optional<JsonNode> b = fromSnapshot(snapshotPayloadJson);
        if (b.isPresent()) {
            return b;
        }
        return usable(parse(desiredConfigJson));
    }

    /** The snapshot's owned block, converted to the target-node shape. */
    public static Optional<JsonNode> fromSnapshot(String payloadJson) {
        Optional<AwsSnapshotCodec.Owned> owned = AwsSnapshotCodec.decodeOwned(payloadJson);
        if (owned.isEmpty()) {
            return Optional.empty();
        }
        AwsSnapshotCodec.Owned o = owned.get();
        ObjectNode n = JSON.createObjectNode();
        n.put("shape", o.shape());
        n.put("capacityRebalance", o.capacityRebalance());
        if (o.hasMaintenancePolicy()) {
            n.set(AwsMaintenancePolicy.KEY, restoreNode(o.instanceMaintenancePolicy()));
        }
        JsonNode mip = o.mixedInstancesPolicy();
        if (mip == null) {
            if (o.launchTemplate() == null) {
                return Optional.empty();
            }
            ObjectNode lt = n.putObject("launchTemplate");
            lt.put("id", o.launchTemplate().id());
            lt.put("name", o.launchTemplate().name());
            lt.put("version", o.launchTemplate().version());
            n.putArray("overrides");
            n.putNull("instancesDistribution");
            return Optional.of(n);
        }
        JsonNode spec = mip.path("launchTemplate").path("spec");
        if (spec.isObject()) {
            n.set("launchTemplate", spec);
        }
        ArrayNode types = n.putArray("overrides");
        mip.path("launchTemplate").path("overrides").forEach(ov -> {
            if (ov.hasNonNull("type")) {
                types.add(ov.get("type").asText());
            }
        });
        ObjectNode dist = JSON.createObjectNode();
        JsonNode d = mip.path("instancesDistribution");
        for (String f : new String[] {"onDemandAllocationStrategy", "onDemandBaseCapacity",
                "onDemandPercentageAboveBaseCapacity", "spotAllocationStrategy", "spotInstancePools"}) {
            if (d.hasNonNull(f)) {
                dist.set(f, d.get(f));
            }
        }
        n.set("instancesDistribution", dist);
        return Optional.of(n);
    }

    /** The snapshot's original policy as a value to write: ABSENT is -1/-1, which clears it (AD-08). */
    static ObjectNode restoreNode(JsonNode saved) {
        ObjectNode p = JSON.createObjectNode();
        p.put("owned", true);
        boolean set = "SET".equals(saved.path("state").asText());
        p.put("min", set && saved.hasNonNull("min") ? saved.get("min").asInt() : AwsMaintenancePolicy.CLEAR);
        p.put("max", set && saved.hasNonNull("max") ? saved.get("max").asInt() : AwsMaintenancePolicy.CLEAR);
        return p;
    }

    private static Optional<JsonNode> usable(JsonNode n) {
        if (n == null || !n.isObject() || !n.has("shape")) {
            return Optional.empty();
        }
        boolean mip = !"LAUNCH_TEMPLATE".equals(n.path("shape").asText());
        if (mip && !n.path("instancesDistribution").isObject()) {
            return Optional.empty();
        }
        return Optional.of(n);
    }

    private static JsonNode parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSON.readTree(json);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
