package com.xammer.cloud.service.autospotting.aws;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xammer.cloud.domain.autospotting.Provider;
import com.xammer.cloud.service.autospotting.spi.SnapshotPayload;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;
import software.amazon.awssdk.services.autoscaling.model.InstancesDistribution;
import software.amazon.awssdk.services.autoscaling.model.LaunchTemplateOverrides;
import software.amazon.awssdk.services.autoscaling.model.LaunchTemplateSpecification;
import software.amazon.awssdk.services.autoscaling.model.TagDescription;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Builds and reads the AWS snapshot payload, {@code schema_version=1} (design-aws.md §5.1). Read
 * side only: {@code takeSnapshot} is a pure describe, and nothing here calls AWS.
 *
 * <p>Two classes of field are captured. {@code owned} fields are what revert restores (FR-REV-01).
 * {@code context} fields are recorded for the three-way diff and support and are never written back
 * (FR-AWS-06, FR-REV-02). The launch template <em>version string</em> is stored as-is ({@code $Default}
 * stays {@code $Default}); the resolved number and a content hash are context only. No user data is
 * captured (FR-SEC-06): the template content is referenced by version and hash, not copied.
 *
 * <p>Tags and policy lists are capped (100 tags, 50 policies, 50 actions) with a {@code truncated}
 * flag. They are context, so truncation never affects restore.
 */
public final class AwsSnapshotCodec {

    public static final int SCHEMA_VERSION = 1;
    static final int MAX_TAGS = 100;
    static final int MAX_LIST = 50;
    public static final String CAPTURED_LIVE = "LIVE";
    public static final String CAPTURED_RECONSTRUCTED = "RECONSTRUCTED";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private AwsSnapshotCodec() {
    }

    /** Extra context a caller may have read beyond the group describe (all optional). */
    public record Context(Integer resolvedLaunchTemplateVersion, String launchTemplateVersionSha256,
                          Integer spotInstances, List<String> lifecycleHooks,
                          List<String> scalingPolicies, List<String> scheduledActions) {
        public static Context none() {
            return new Context(null, null, null, List.of(), List.of(), List.of());
        }
    }

    /** The owned part of a decoded payload (what revert restores). */
    public record Owned(String shape, AwsGroupFacts.LtSpec launchTemplate, boolean capacityRebalance,
                        JsonNode mixedInstancesPolicy, String ownedFingerprint, String capturedFrom,
                        JsonNode instanceMaintenancePolicy) {
        /** True when the snapshot recorded the policy (older snapshots did not, and revert leaves the policy alone). */
        public boolean hasMaintenancePolicy() {
            return instanceMaintenancePolicy != null && instanceMaintenancePolicy.isObject();
        }
    }

    /** Capture a live snapshot. {@code exact=false} (with reasons) when the original cannot be restored exactly. */
    public static SnapshotPayload encode(String region, AutoScalingGroup g, Context ctx, boolean reconstructed) {
        Context c = ctx == null ? Context.none() : ctx;
        AwsGroupFacts facts = AwsGroupFacts.from(region, g);
        List<String> reasons = new ArrayList<>();
        ObjectNode root = MAPPER.createObjectNode();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("asgArn", g.autoScalingGroupARN());
        root.put("region", region);
        root.put("name", g.autoScalingGroupName());
        root.put("capturedFrom", reconstructed ? CAPTURED_RECONSTRUCTED : CAPTURED_LIVE);

        ObjectNode owned = root.putObject("owned");
        if (g.mixedInstancesPolicy() != null) {
            owned.put("shape", "MIXED_INSTANCES_POLICY");
            owned.putNull("launchTemplate");
            owned.set("mixedInstancesPolicy", mixed(g, reasons));
        } else {
            owned.put("shape", "LAUNCH_TEMPLATE");
            owned.set("launchTemplate", spec(g.launchTemplate()));
            owned.putNull("mixedInstancesPolicy");
        }
        owned.put("capacityRebalance", facts.capacityRebalance());
        // AD-08: the original maintenance policy, including "absent", is what revert restores.
        ObjectNode imp = owned.putObject("instanceMaintenancePolicy");
        if (facts.minHealthyPercentage() == null && facts.maxHealthyPercentage() == null) {
            imp.put("state", "ABSENT");
        } else {
            imp.put("state", "SET");
            putInt(imp, "min", facts.minHealthyPercentage());
            putInt(imp, "max", facts.maxHealthyPercentage());
        }

        ObjectNode ctxNode = root.putObject("context");
        ctxNode.put("min", facts.min());
        ctxNode.put("max", facts.max());
        ctxNode.put("desired", facts.desired());
        ctxNode.put("desiredCapacityType", facts.desiredCapacityType());
        ctxNode.put("vpcZoneIdentifier", g.vpcZoneIdentifier());
        ctxNode.put("healthCheckType", facts.healthCheckType());
        putInt(ctxNode, "healthCheckGracePeriod", facts.healthCheckGracePeriod());
        putInt(ctxNode, "defaultInstanceWarmup", facts.defaultInstanceWarmup());
        putInt(ctxNode, "maxHealthyPercentage", facts.maxHealthyPercentage());
        putInt(ctxNode, "maxInstanceLifetime", g.maxInstanceLifetime());
        ctxNode.set("terminationPolicies", strings(facts.terminationPolicies(), MAX_LIST));
        ctxNode.set("suspendedProcesses", strings(facts.suspendedProcesses(), MAX_LIST));
        boolean truncated = g.tags().size() > MAX_TAGS;
        ArrayNode tags = ctxNode.putArray("tags");
        for (TagDescription t : g.tags().stream().limit(MAX_TAGS).toList()) {
            ObjectNode tn = tags.addObject();
            tn.put("key", t.key());
            tn.put("value", t.value());
            tn.put("propagateAtLaunch", Boolean.TRUE.equals(t.propagateAtLaunch()));
        }
        ctxNode.set("targetGroupArns", strings(facts.targetGroupArns(), MAX_LIST));
        ctxNode.set("lifecycleHooks", strings(c.lifecycleHooks(), MAX_LIST));
        ctxNode.put("warmPool", facts.warmPoolConfigured());
        ctxNode.set("scalingPolicies", strings(c.scalingPolicies(), MAX_LIST));
        ctxNode.set("scheduledActions", strings(c.scheduledActions(), MAX_LIST));
        truncated |= c.lifecycleHooks().size() > MAX_LIST || c.scalingPolicies().size() > MAX_LIST
                || c.scheduledActions().size() > MAX_LIST;
        ctxNode.put("truncated", truncated);
        if (c.resolvedLaunchTemplateVersion() != null) {
            ctxNode.put("resolvedLaunchTemplateVersion", c.resolvedLaunchTemplateVersion());
        }
        if (c.launchTemplateVersionSha256() != null) {
            ctxNode.put("launchTemplateVersionSha256", c.launchTemplateVersionSha256());
        }
        if (c.spotInstances() != null) {
            ObjectNode mix = ctxNode.putObject("instanceMix");
            mix.put("onDemand", Math.max(0, facts.instances().size() - c.spotInstances()));
            mix.put("spot", c.spotInstances());
        }
        root.put("ownedFingerprint", AwsFingerprint.of(facts));

        if (reconstructed) {
            reasons.add("RECONSTRUCTED_FROM_LEGACY_STATE");
        }
        if (facts.launchConfigurationName() != null) {
            reasons.add("LAUNCH_CONFIGURATION_GROUP");
        }
        String json = write(root);
        String sha = AwsFingerprint.sha256(json);
        return new SnapshotPayload(Provider.AWS, SCHEMA_VERSION, reasons.isEmpty(), reasons, json, sha);
    }

    /** Read the owned part back from a stored payload. */
    public static Optional<Owned> decodeOwned(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode root = MAPPER.readTree(payloadJson);
            JsonNode owned = root.path("owned");
            if (owned.isMissingNode() || root.path("schemaVersion").asInt(0) != SCHEMA_VERSION) {
                return Optional.empty();
            }
            JsonNode lt = owned.path("launchTemplate");
            AwsGroupFacts.LtSpec spec = lt.isObject()
                    ? new AwsGroupFacts.LtSpec(text(lt, "id"), text(lt, "name"), text(lt, "version"))
                    : null;
            JsonNode mixed = owned.path("mixedInstancesPolicy");
            return Optional.of(new Owned(owned.path("shape").asText(null), spec,
                    owned.path("capacityRebalance").asBoolean(false),
                    mixed.isObject() ? mixed : null,
                    root.path("ownedFingerprint").asText(null),
                    root.path("capturedFrom").asText(null),
                    owned.path("instanceMaintenancePolicy").isObject() ? owned.get("instanceMaintenancePolicy") : null));
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
    }

    private static ObjectNode mixed(AutoScalingGroup g, List<String> reasons) {
        ObjectNode m = MAPPER.createObjectNode();
        ObjectNode lt = m.putObject("launchTemplate");
        var src = g.mixedInstancesPolicy().launchTemplate();
        if (src != null) {
            lt.set("spec", spec(src.launchTemplateSpecification()));
            ArrayNode overrides = lt.putArray("overrides");
            for (LaunchTemplateOverrides o : src.overrides()) {
                ObjectNode on = overrides.addObject();
                on.put("type", o.instanceType());
                on.put("weight", o.weightedCapacity());
                on.set("ltSpec", spec(o.launchTemplateSpecification()));
                boolean req = o.instanceRequirements() != null;
                on.put("instanceRequirements", req);
                if (req) {
                    reasons.add("INSTANCE_REQUIREMENTS_NOT_CAPTURED");
                }
            }
        }
        InstancesDistribution d = g.mixedInstancesPolicy().instancesDistribution();
        ObjectNode dn = m.putObject("instancesDistribution");
        if (d != null) {
            dn.put("onDemandAllocationStrategy", d.onDemandAllocationStrategy());
            putInt(dn, "onDemandBaseCapacity", d.onDemandBaseCapacity());
            putInt(dn, "onDemandPercentageAboveBaseCapacity", d.onDemandPercentageAboveBaseCapacity());
            dn.put("spotAllocationStrategy", d.spotAllocationStrategy());
            putInt(dn, "spotInstancePools", d.spotInstancePools());
            dn.put("spotMaxPrice", d.spotMaxPrice());
        }
        return m;
    }

    private static JsonNode spec(LaunchTemplateSpecification s) {
        if (s == null) {
            return MAPPER.nullNode();
        }
        ObjectNode n = MAPPER.createObjectNode();
        n.put("id", s.launchTemplateId());
        n.put("name", s.launchTemplateName());
        n.put("version", s.version());
        return n;
    }

    private static ArrayNode strings(List<String> values, int cap) {
        ArrayNode a = MAPPER.createArrayNode();
        values.stream().limit(cap).forEach(a::add);
        return a;
    }

    private static void putInt(ObjectNode n, String field, Integer v) {
        if (v == null) {
            n.putNull(field);
        } else {
            n.put(field, v);
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isNull() || v.isMissingNode() ? null : v.asText();
    }

    private static String write(JsonNode n) {
        try {
            return MAPPER.writeValueAsString(MAPPER.treeToValue(n, Object.class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("snapshot serialization failed", e);
        }
    }
}
