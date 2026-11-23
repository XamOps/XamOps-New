package com.xammer.cloud.service.autospotting.aws;

import com.fasterxml.jackson.databind.JsonNode;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;
import software.amazon.awssdk.services.autoscaling.model.InstanceMaintenancePolicy;
import software.amazon.awssdk.services.autoscaling.model.InstancesDistribution;
import software.amazon.awssdk.services.autoscaling.model.LaunchTemplate;
import software.amazon.awssdk.services.autoscaling.model.LaunchTemplateOverrides;
import software.amazon.awssdk.services.autoscaling.model.LaunchTemplateSpecification;
import software.amazon.awssdk.services.autoscaling.model.MixedInstancesPolicy;
import software.amazon.awssdk.services.autoscaling.model.UpdateAutoScalingGroupRequest;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds the one {@code UpdateAutoScalingGroup} request a conversion sends (design-aws.md §2.4,
 * FR-AWS-06, FR-CMP-04). Pure: it reads the live group and the target configuration JSON (the plan's
 * {@code target}, same shape as {@code AwsSpotProvider}'s target node) and returns the request.
 *
 * <p>Owned fields only. Two shapes:
 * <ul>
 *   <li><b>Standalone launch template:</b> {@code MixedInstancesPolicy.LaunchTemplate} with the live
 *       specification copied verbatim (id or name, and the version string as-is) and the target
 *       overrides, plus the profile's {@code InstancesDistribution} and {@code CapacityRebalance=true}.</li>
 *   <li><b>Existing MixedInstancesPolicy:</b> {@code InstancesDistribution} and
 *       {@code CapacityRebalance} only; {@code LaunchTemplate} is omitted so the customer's overrides
 *       stay as they are. Only when the target adds instance types does the request carry the live
 *       specification and the live overrides plus the added types.</li>
 * </ul>
 * Never sent: {@code DesiredCapacity}, {@code MinSize}, {@code MaxSize}, health-check fields, subnets,
 * zones, tags, and {@code SpotMaxPrice}. {@link #forbiddenFields} lets a step verify that before the
 * call. There is no instance-refresh field on the request type at all.
 */
public final class AwsUpdateRequestBuilder {

    private AwsUpdateRequestBuilder() {
    }

    /**
     * Build the request.
     *
     * @throws IllegalStateException when the live group is a Launch Configuration group (rejected 422
     *                               before any step runs) or the target is malformed
     */
    public static UpdateAutoScalingGroupRequest build(AutoScalingGroup live, JsonNode target) {
        if (live == null || target == null || !target.isObject()) {
            throw new IllegalStateException("live group and target configuration are required");
        }
        if ("LAUNCH_TEMPLATE".equals(target.path("shape").asText())) {
            return buildLaunchTemplateShape(live, target);
        }
        MixedInstancesPolicy liveMip = live.mixedInstancesPolicy();
        if (liveMip == null && (live.launchTemplate() == null || live.launchConfigurationName() != null)) {
            throw new IllegalStateException("only launch-template groups can be converted");
        }
        InstancesDistribution distribution = distribution(target.path("instancesDistribution"));
        List<String> targetTypes = types(target.path("overrides"));

        MixedInstancesPolicy.Builder policy = MixedInstancesPolicy.builder().instancesDistribution(distribution);
        if (liveMip == null) {
            policy.launchTemplate(LaunchTemplate.builder()
                    .launchTemplateSpecification(copySpec(live.launchTemplate()))
                    .overrides(overrides(targetTypes))
                    .build());
        } else {
            List<LaunchTemplateOverrides> liveOverrides = liveMip.launchTemplate() == null
                    ? List.of() : liveMip.launchTemplate().overrides();
            Set<String> liveTypes = new LinkedHashSet<>();
            liveOverrides.forEach(o -> {
                if (o.instanceType() != null) {
                    liveTypes.add(o.instanceType());
                }
            });
            List<String> added = targetTypes.stream().filter(t -> !liveTypes.contains(t)).toList();
            if (!added.isEmpty() && liveMip.launchTemplate() != null
                    && liveMip.launchTemplate().launchTemplateSpecification() != null) {
                List<LaunchTemplateOverrides> merged = new ArrayList<>(liveOverrides);
                merged.addAll(overrides(added));
                policy.launchTemplate(LaunchTemplate.builder()
                        .launchTemplateSpecification(copySpec(liveMip.launchTemplate().launchTemplateSpecification()))
                        .overrides(merged)
                        .build());
            }
        }
        UpdateAutoScalingGroupRequest.Builder request = UpdateAutoScalingGroupRequest.builder()
                .autoScalingGroupName(live.autoScalingGroupName())
                .mixedInstancesPolicy(policy.build())
                .capacityRebalance(target.path("capacityRebalance").asBoolean(true));
        withMaintenancePolicy(request, target);
        return request.build();
    }

    /**
     * A prior configuration that was a plain launch template (a compensation target only): the live
     * specification is not used; the saved one is sent as the group's launch template.
     */
    private static UpdateAutoScalingGroupRequest buildLaunchTemplateShape(AutoScalingGroup live, JsonNode target) {
        JsonNode lt = target.path("launchTemplate");
        if (!lt.isObject() || (!lt.hasNonNull("id") && !lt.hasNonNull("name"))) {
            throw new IllegalStateException("prior launch template is missing");
        }
        LaunchTemplateSpecification.Builder spec = LaunchTemplateSpecification.builder()
                .version(lt.path("version").asText("$Default"));
        if (lt.hasNonNull("id")) {
            spec.launchTemplateId(lt.get("id").asText());
        } else {
            spec.launchTemplateName(lt.get("name").asText());
        }
        UpdateAutoScalingGroupRequest.Builder request = UpdateAutoScalingGroupRequest.builder()
                .autoScalingGroupName(live.autoScalingGroupName())
                .launchTemplate(spec.build())
                .capacityRebalance(target.path("capacityRebalance").asBoolean(false));
        withMaintenancePolicy(request, target);
        return request.build();
    }

    /**
     * Adds {@code InstanceMaintenancePolicy} when the configuration carries a value for it (design-aws.md AD-08):
     * 100/200 for a policy XamOps owns, or the saved original (-1/-1 clears it) for a restore. Taking it from the
     * configuration means no program can drop it by accident, and a customer's own policy is never in the target.
     */
    public static void withMaintenancePolicy(UpdateAutoScalingGroupRequest.Builder request, JsonNode config) {
        if (AwsMaintenancePolicy.hasValue(config)) {
            JsonNode p = config.path(AwsMaintenancePolicy.KEY);
            request.instanceMaintenancePolicy(InstanceMaintenancePolicy.builder()
                    .minHealthyPercentage(p.get("min").asInt())
                    .maxHealthyPercentage(p.get("max").asInt()).build());
        }
    }

    /** The request fields that must never be present; empty means the request is owned-fields only. */
    public static List<String> forbiddenFields(UpdateAutoScalingGroupRequest r) {
        List<String> bad = new ArrayList<>();
        if (r.desiredCapacity() != null) {
            bad.add("DesiredCapacity");
        }
        if (r.minSize() != null) {
            bad.add("MinSize");
        }
        if (r.maxSize() != null) {
            bad.add("MaxSize");
        }
        if (r.healthCheckType() != null) {
            bad.add("HealthCheckType");
        }
        if (r.healthCheckGracePeriod() != null) {
            bad.add("HealthCheckGracePeriod");
        }
        if (r.vpcZoneIdentifier() != null) {
            bad.add("VPCZoneIdentifier");
        }
        if (r.hasAvailabilityZones()) {
            bad.add("AvailabilityZones");
        }
        if (r.defaultCooldown() != null) {
            bad.add("DefaultCooldown");
        }
        if (r.hasTerminationPolicies()) {
            bad.add("TerminationPolicies");
        }
        if (r.newInstancesProtectedFromScaleIn() != null) {
            bad.add("NewInstancesProtectedFromScaleIn");
        }
        if (r.launchTemplate() != null) {
            bad.add("LaunchTemplate");
        }
        if (r.launchConfigurationName() != null) {
            bad.add("LaunchConfigurationName");
        }
        if (r.defaultInstanceWarmup() != null) {
            bad.add("DefaultInstanceWarmup");
        }
        MixedInstancesPolicy m = r.mixedInstancesPolicy();
        if (m != null && m.instancesDistribution() != null && m.instancesDistribution().spotMaxPrice() != null) {
            bad.add("InstancesDistribution.SpotMaxPrice");
        }
        return bad;
    }

    private static InstancesDistribution distribution(JsonNode d) {
        InstancesDistribution.Builder b = InstancesDistribution.builder();
        if (d.hasNonNull("onDemandAllocationStrategy")) {
            b.onDemandAllocationStrategy(d.get("onDemandAllocationStrategy").asText());
        }
        if (d.hasNonNull("onDemandBaseCapacity")) {
            b.onDemandBaseCapacity(d.get("onDemandBaseCapacity").asInt());
        }
        if (d.hasNonNull("onDemandPercentageAboveBaseCapacity")) {
            b.onDemandPercentageAboveBaseCapacity(d.get("onDemandPercentageAboveBaseCapacity").asInt());
        }
        String strategy = d.path("spotAllocationStrategy").asText(null);
        if (strategy != null) {
            b.spotAllocationStrategy(strategy);
        }
        // SpotInstancePools only with lowest-price, the one strategy that uses it; never SpotMaxPrice.
        if ("lowest-price".equals(strategy) && d.hasNonNull("spotInstancePools")) {
            b.spotInstancePools(d.get("spotInstancePools").asInt());
        }
        return b.build();
    }

    private static LaunchTemplateSpecification copySpec(LaunchTemplateSpecification s) {
        LaunchTemplateSpecification.Builder b = LaunchTemplateSpecification.builder().version(s.version());
        if (s.launchTemplateId() != null) {
            b.launchTemplateId(s.launchTemplateId());
        } else {
            b.launchTemplateName(s.launchTemplateName());
        }
        return b.build();
    }

    private static List<LaunchTemplateOverrides> overrides(List<String> types) {
        List<LaunchTemplateOverrides> out = new ArrayList<>();
        for (String t : types) {
            out.add(LaunchTemplateOverrides.builder().instanceType(t).build());
        }
        return out;
    }

    private static List<String> types(JsonNode array) {
        List<String> out = new ArrayList<>();
        if (array != null && array.isArray()) {
            array.forEach(n -> {
                String t = n.asText(null);
                if (t != null && !t.isBlank() && !out.contains(t)) {
                    out.add(t);
                }
            });
        }
        return out;
    }
}
