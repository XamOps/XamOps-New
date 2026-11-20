package com.xammer.cloud.service.autospotting.aws;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Compares a live group's owned fields with a configuration JSON (the plan's target, or a prior
 * configuration), for the {@code UPDATE_DISTRIBUTION} probe and {@code VERIFY_TARGET} (design-aws.md
 * §2.3 steps 3 and 5). Owned fields are the mixed-instances policy shape, the instance-type overrides,
 * the distribution and {@code CapacityRebalance}; capacity bounds and health checks are not owned.
 * A distribution value the config leaves null is not compared.
 */
public final class AwsOwnedFields {

    private AwsOwnedFields() {
    }

    /** The names of the owned fields that differ; empty means the live group matches the config. */
    public static List<String> mismatches(AwsGroupFacts live, JsonNode config) {
        List<String> bad = new ArrayList<>();
        boolean wantMip = !"LAUNCH_TEMPLATE".equals(config.path("shape").asText("MIXED_INSTANCES_POLICY"));
        if (live.mixedInstancesPolicy() != wantMip) {
            bad.add("shape");
        }
        JsonNode lt = config.path("launchTemplate");
        if (lt.isObject() && live.launchTemplate() != null) {
            String want = lt.hasNonNull("id") ? lt.get("id").asText() : lt.path("name").asText(null);
            if (want != null && !want.equals(live.launchTemplate().idOrName())) {
                bad.add("launchTemplate");
            }
        }
        if (config.path("capacityRebalance").asBoolean(false) != live.capacityRebalance()) {
            bad.add("capacityRebalance");
        }
        if (!wantMip) {
            return bad;
        }
        JsonNode d = config.path("instancesDistribution");
        compare(bad, "onDemandAllocationStrategy", d, live.onDemandAllocationStrategy());
        compare(bad, "onDemandBaseCapacity", d, live.onDemandBase());
        compare(bad, "onDemandPercentageAboveBaseCapacity", d, live.onDemandPercentageAboveBase());
        compare(bad, "spotAllocationStrategy", d, live.spotAllocationStrategy());
        if ("lowest-price".equals(d.path("spotAllocationStrategy").asText(null)) && d.hasNonNull("spotInstancePools")
                && !java.util.Objects.equals(d.get("spotInstancePools").asInt(), live.spotInstancePools())) {
            bad.add("spotInstancePools");
        }
        JsonNode types = config.path("overrides");
        if (types.isArray() && !types.isEmpty() && !live.attributeBasedOverrides()) {
            TreeSet<String> want = new TreeSet<>();
            types.forEach(n -> want.add(n.asText()));
            if (!want.equals(new TreeSet<>(live.overrideTypes()))) {
                bad.add("overrides");
            }
        }
        return bad;
    }

    private static void compare(List<String> bad, String field, JsonNode d, Object live) {
        JsonNode want = d.path(field);
        if (want.isMissingNode() || want.isNull()) {
            return;
        }
        String w = want.asText();
        if (live == null || !w.equals(String.valueOf(live))) {
            bad.add(field);
        }
    }
}
