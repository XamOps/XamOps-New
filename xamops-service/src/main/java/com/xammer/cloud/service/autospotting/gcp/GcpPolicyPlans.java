package com.xammer.cloud.service.autospotting.gcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xammer.cloud.domain.autospotting.OperationKind;
import com.xammer.cloud.service.autospotting.spi.Profile;

import java.util.Iterator;
import java.util.Map;

/**
 * The plan body of a policy-only change (design-gcp.md §3.5): MIG_POLICY_UPDATE, and a CONFIG_APPLY that changes
 * only the update policy. The request carries the edit as the profile's parameters JSON,
 * {@code {"updatePolicy":{...},"targetSize":n}}, with only the fields the user edited. The plan shows the policy
 * diff and records the policy read now as {@code prior}, which the compensation restores.
 *
 * <p>Rules: {@code minReadySeconds} is dropped (not a v1 field, V-GCP-03); {@code type} is sent only if edited;
 * {@code targetSize} only if edited and never for a MIG with an autoscaler (rejected here, 422 at the API); the
 * merged settings need at least one of surge and unavailable above 0, regional fixed values 0 or at least the
 * zone count, and both at most the target size.
 */
public final class GcpPolicyPlans {
    private static final ObjectMapper JSON = new ObjectMapper();

    private GcpPolicyPlans() {
    }

    public record Built(ObjectNode current, ObjectNode target, ArrayNode diff, String problem) {
    }

    /** True when {@code profile} carries a policy edit rather than a profile id. */
    public static boolean isPolicyEdit(Profile profile) {
        JsonNode n = params(profile);
        return n != null && (n.has("updatePolicy") || n.has("targetSize"));
    }

    private static JsonNode params(Profile profile) {
        if (profile == null || profile.parametersJson() == null || profile.parametersJson().isBlank()) {
            return null;
        }
        try {
            return JSON.readTree(profile.parametersJson());
        } catch (java.io.IOException e) {
            return null;
        }
    }

    public static Built build(OperationKind kind, Profile profile, GcpMigFacts g) {
        JsonNode in = params(profile);
        ObjectNode edit = JSON.createObjectNode();
        ObjectNode editPolicy = edit.putObject("updatePolicy");
        if (in != null && in.path("updatePolicy").isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = in.path("updatePolicy").fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                if (!"minReadySeconds".equals(e.getKey())) {
                    editPolicy.set(e.getKey(), e.getValue().deepCopy());
                }
            }
            for (String f : new String[] {"maxSurge", "maxUnavailable"}) {
                if (editPolicy.path(f).isObject()) {
                    ((ObjectNode) editPolicy.get(f)).remove("calculated");
                }
            }
        }
        if (editPolicy.isEmpty()) {
            edit.remove("updatePolicy");
        }
        boolean editsSize = in != null && in.hasNonNull("targetSize");
        if (editsSize) {
            edit.put("targetSize", in.path("targetSize").asInt());
        }

        ObjectNode current = JSON.createObjectNode();
        current.put("shape", "MIG");
        current.put("name", g.name());
        current.put("location", g.location());
        current.set("updatePolicy", g.updatePolicy().deepCopy());
        current.put("targetSize", g.targetSize());
        current.put("autoscaled", g.autoscalerLink() != null);

        ObjectNode prior = JSON.createObjectNode();
        ObjectNode priorPolicy = prior.putObject("updatePolicy");
        editPolicy.fieldNames().forEachRemaining(f -> {
            if (g.updatePolicy().has(f)) {
                priorPolicy.set(f, g.updatePolicy().get(f).deepCopy());
            }
        });
        if (editsSize) {
            prior.put("targetSize", g.targetSize());
        }

        ObjectNode merged = g.updatePolicy().deepCopy();
        editPolicy.fields().forEachRemaining(e -> merged.set(e.getKey(), e.getValue().deepCopy()));
        String problem = null;
        if (edit.isEmpty()) {
            problem = "GCP_POLICY_NO_CHANGE";
        } else if (editsSize && g.autoscalerLink() != null) {
            problem = "GCP_TARGET_SIZE_AUTOSCALED";
        } else {
            String v = GcpRolloutPolicy.validateEdit(merged, g.regional(), g.regional() ? g.distributionZones().size() : 1,
                    editsSize ? edit.path("targetSize").asInt() : g.targetSize());
            if (v != null) {
                problem = "GCP_POLICY_" + v;
            }
        }

        ObjectNode target = JSON.createObjectNode();
        target.put("shape", "MIG");
        target.put("policyOnly", true);
        target.set("edit", edit);
        target.set("prior", prior);
        if (kind == OperationKind.CONFIG_APPLY) {
            // The saved policy becomes the customer policy later rollouts restore (§3.5).
            target.set("customerUpdatePolicy", merged);
        }

        ArrayNode diff = JSON.createArrayNode();
        editPolicy.fields().forEachRemaining(e -> diff.addObject().put("field", "updatePolicy." + e.getKey())
                .put("from", g.updatePolicy().has(e.getKey()) ? g.updatePolicy().get(e.getKey()).toString() : null)
                .put("to", e.getValue().toString()));
        if (editsSize) {
            diff.addObject().put("field", "targetSize").put("from", String.valueOf(g.targetSize()))
                    .put("to", String.valueOf(edit.path("targetSize").asInt()));
        }
        return new Built(current, target, diff, problem);
    }
}
