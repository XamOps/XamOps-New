package com.xammer.cloud.service.autospotting.gcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.Set;
import java.util.UUID;

/**
 * The Spot template clone (design-gcp.md §2.3-§2.5), a pure function: source template JSON in, the
 * {@code instanceTemplates.insert} body out. The copy is structural, never an allowlist: every field of
 * {@code properties} survives (GPUs, Shielded and Confidential VM, IP forwarding, reservations, placement,
 * sole-tenancy and any field XamOps does not know). Only these change:
 * <ul>
 *   <li>{@code scheduling.provisioningModel=SPOT}, {@code instanceTerminationAction=DELETE} (DC-02),
 *       {@code automaticRestart=false}, {@code onHostMaintenance=TERMINATE}; the legacy
 *       {@code preemptible} flag is removed; every other scheduling sub-field is kept;</li>
 *   <li>the one label {@code xamops-autospotting=<groupId>} is added to {@code properties.labels}
 *       (PO-20, C-08); existing labels are kept;</li>
 *   <li>output-only fields are dropped (deny-list below).</li>
 * </ul>
 * The resource-level {@code id}, {@code selfLink}, {@code creationTimestamp}, {@code kind} and
 * {@code region} are never copied. The description is the discovery marker; the registry, not the
 * description, authorizes deletion.
 */
public final class GcpTemplateCloner {
    public static final String LABEL_KEY = "xamops-autospotting";
    private static final ObjectMapper CANONICAL = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    /** [Output Only] field names that appear inside {@code properties} (Compute v1 InstanceProperties and nested types). */
    private static final Set<String> OUTPUT_ONLY = Set.of("kind", "fingerprint", "selfLink", "id", "creationTimestamp");
    private static final Set<String> FREE_KEY_MAPS = Set.of("labels", "resourceManagerTags", "resourceLabels");
    private static final int MAX_NAME = 63;

    private GcpTemplateCloner() {
    }

    /** The clone: the insert body, its canonical sha256 and the name. */
    public record Clone(ObjectNode body, String name, String sha256) {
    }

    /** {@code <migPrefix>-xas-<opShort>}: at most 63 characters, RFC 1035. Deterministic for an op, so a re-issued step computes the same name. */
    public static String nameFor(String migName, UUID operationId) {
        String suffix = "-xas-" + operationId.toString().replace("-", "").substring(0, 8);
        String prefix = migName == null ? "mig" : migName;
        int room = MAX_NAME - suffix.length();
        if (prefix.length() > room) {
            prefix = prefix.substring(0, room);
        }
        while (prefix.endsWith("-")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        return prefix + suffix;
    }

    public static Clone clone(JsonNode source, String sourceName, UUID groupId, String migName, UUID operationId) {
        if (source == null || !source.path("properties").isObject()) {
            throw new IllegalArgumentException("the source template has no properties");
        }
        ObjectNode properties = source.path("properties").deepCopy();
        stripOutputOnly(properties);
        ObjectNode scheduling = properties.has("scheduling") && properties.get("scheduling").isObject()
                ? (ObjectNode) properties.get("scheduling") : properties.putObject("scheduling");
        scheduling.remove("preemptible");
        scheduling.put("provisioningModel", "SPOT");
        scheduling.put("instanceTerminationAction", "DELETE");
        scheduling.put("automaticRestart", false);
        scheduling.put("onHostMaintenance", "TERMINATE");
        ObjectNode labels = properties.has("labels") && properties.get("labels").isObject()
                ? (ObjectNode) properties.get("labels") : properties.putObject("labels");
        labels.put(LABEL_KEY, groupId.toString());

        String name = nameFor(migName, operationId);
        ObjectNode body = CANONICAL.createObjectNode();
        body.put("name", name);
        body.put("description", "XamOps AutoSpotting copy of " + sourceName + " for group " + groupId);
        body.set("properties", properties);
        return new Clone(body, name, GcpFingerprint.sha256(canonical(body)));
    }

    /** Drop output-only fields at every level of {@code properties}. */
    static void stripOutputOnly(JsonNode node) {
        if (node.isObject()) {
            Iterator<String> names = node.fieldNames();
            ObjectNode obj = (ObjectNode) node;
            java.util.List<String> drop = new java.util.ArrayList<>();
            while (names.hasNext()) {
                String n = names.next();
                if (OUTPUT_ONLY.contains(n)) {
                    drop.add(n);
                }
            }
            drop.forEach(obj::remove);
            // Customer-keyed maps are data, never schema: a label named "kind" or "id" must survive.
            obj.fields().forEachRemaining(e -> {
                if (!FREE_KEY_MAPS.contains(e.getKey())) {
                    stripOutputOnly(e.getValue());
                }
            });
        } else if (node.isArray()) {
            ((ArrayNode) node).elements().forEachRemaining(GcpTemplateCloner::stripOutputOnly);
        }
    }

    public static String canonical(JsonNode body) {
        try {
            return CANONICAL.writeValueAsString(CANONICAL.treeToValue(body, Object.class));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("template serialization failed", e);
        }
    }

    /** True when {@code template} (a template read from the cloud) is the Spot copy with {@code expectedSha}. */
    public static boolean sameBody(JsonNode cloudTemplate, String expectedSha) {
        if (cloudTemplate == null || !cloudTemplate.path("properties").isObject()) {
            return false;
        }
        ObjectNode b = CANONICAL.createObjectNode();
        b.put("name", cloudTemplate.path("name").asText());
        b.put("description", cloudTemplate.path("description").asText());
        ObjectNode props = cloudTemplate.path("properties").deepCopy();
        stripOutputOnly(props);
        b.set("properties", props);
        return GcpFingerprint.sha256(canonical(b)).equals(expectedSha);
    }

    /**
     * True when {@code cloudTemplate} is the template this clone produced: the exact canonical body, or (the cloud
     * may add defaulted fields) the XamOps description marker for this group, the SPOT scheduling model and the
     * attribution label together. A template that merely shares our name never matches.
     */
    public static boolean isOurs(JsonNode cloudTemplate, String expectedSha, UUID groupId) {
        if (sameBody(cloudTemplate, expectedSha)) {
            return true;
        }
        return cloudTemplate != null
                && cloudTemplate.path("description").asText("").startsWith("XamOps AutoSpotting copy of ")
                && cloudTemplate.path("description").asText("").endsWith(" for group " + groupId)
                && "SPOT".equals(cloudTemplate.path("properties").path("scheduling").path("provisioningModel").asText())
                && groupId.toString().equals(cloudTemplate.path("properties").path("labels").path(LABEL_KEY).asText());
    }
}
