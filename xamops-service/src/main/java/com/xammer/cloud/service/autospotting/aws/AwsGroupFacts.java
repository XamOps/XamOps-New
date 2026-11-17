package com.xammer.cloud.service.autospotting.aws;

import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;
import software.amazon.awssdk.services.autoscaling.model.InstancesDistribution;
import software.amazon.awssdk.services.autoscaling.model.LaunchTemplateOverrides;
import software.amazon.awssdk.services.autoscaling.model.LaunchTemplateSpecification;
import software.amazon.awssdk.services.autoscaling.model.MixedInstancesPolicy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The facts about one Auto Scaling group that the AWS provider reasons over, extracted once from a
 * {@code DescribeAutoScalingGroups} result (design-aws.md §2.1, §5.1, §7). A plain value type: the
 * eligibility rules, fingerprint, snapshot codec and discovery all take this rather than the SDK
 * object, so they are pure and need no cloud access.
 */
public record AwsGroupFacts(
        String region,
        String name,
        String arn,
        String launchConfigurationName,
        LtSpec launchTemplate,
        boolean mixedInstancesPolicy,
        List<String> overrideTypes,
        boolean attributeBasedOverrides,
        Integer onDemandBase,
        Integer onDemandPercentageAboveBase,
        String onDemandAllocationStrategy,
        String spotAllocationStrategy,
        Integer spotInstancePools,
        String spotMaxPrice,
        boolean capacityRebalance,
        int min,
        int max,
        int desired,
        String desiredCapacityType,
        String healthCheckType,
        Integer healthCheckGracePeriod,
        Integer defaultInstanceWarmup,
        List<String> subnetIds,
        Set<String> availabilityZones,
        Map<String, String> tags,
        List<String> targetGroupArns,
        List<String> loadBalancerNames,
        boolean newInstancesProtectedFromScaleIn,
        Integer maxHealthyPercentage,
        boolean warmPoolConfigured,
        List<String> terminationPolicies,
        List<String> suspendedProcesses,
        List<InstanceFacts> instances) {

    /** A launch template reference: id or name, plus the version string as-is ({@code $Default}, {@code $Latest}, a number). */
    public record LtSpec(String id, String name, String version) {
        public String idOrName() {
            return id != null ? id : name;
        }
    }

    /** One member instance as the group describe reports it. */
    public record InstanceFacts(String id, String type, String az, String lifecycleState, boolean protectedFromScaleIn) {
    }

    /** The XamOps ownership marker tag (design-core.md §2.5 rule 3). */
    public static final String MARKER_TAG = "xamops:autospotting:group-id";
    /** The legacy AutoSpotting tag (design-core.md §2.5 rule 2, A-03). */
    public static final String LEGACY_TAG = "spot-enabled";

    public AwsGroupFacts {
        overrideTypes = overrideTypes == null ? List.of() : List.copyOf(overrideTypes);
        subnetIds = subnetIds == null ? List.of() : List.copyOf(subnetIds);
        availabilityZones = availabilityZones == null ? Set.of() : Set.copyOf(availabilityZones);
        tags = tags == null ? Map.of() : Map.copyOf(tags);
        targetGroupArns = targetGroupArns == null ? List.of() : List.copyOf(targetGroupArns);
        loadBalancerNames = loadBalancerNames == null ? List.of() : List.copyOf(loadBalancerNames);
        terminationPolicies = terminationPolicies == null ? List.of() : List.copyOf(terminationPolicies);
        suspendedProcesses = suspendedProcesses == null ? List.of() : List.copyOf(suspendedProcesses);
        instances = instances == null ? List.of() : List.copyOf(instances);
    }

    /** The group key {@code region/asgName} (design-core.md §2.1). */
    public String groupKey() {
        return region + "/" + name;
    }

    /** True when the group already runs part of its capacity on Spot (§2.5 rule 4): OnDemandPercentageAboveBaseCapacity &lt; 100. */
    public boolean hasSpotShare() {
        return mixedInstancesPolicy && onDemandPercentageAboveBase != null && onDemandPercentageAboveBase < 100;
    }

    /** True when the group carries the XamOps group-id marker tag. */
    public boolean hasMarker() {
        return tags.containsKey(MARKER_TAG);
    }

    /** True when the group carries the legacy {@code spot-enabled=true} tag. */
    public boolean hasLegacyTag() {
        return "true".equalsIgnoreCase(tags.get(LEGACY_TAG));
    }

    /** The XamOps group id the marker names, or null. */
    public String markerGroupId() {
        return tags.get(MARKER_TAG);
    }

    /** Instances protected from scale-in. */
    public long protectedInstanceCount() {
        return instances.stream().filter(InstanceFacts::protectedFromScaleIn).count();
    }

    /** Extract the facts from an SDK group. */
    public static AwsGroupFacts from(String region, AutoScalingGroup g) {
        LtSpec lt = null;
        boolean mixed = false;
        List<String> overrides = new ArrayList<>();
        boolean abs = false;
        InstancesDistribution dist = null;
        MixedInstancesPolicy mip = g.mixedInstancesPolicy();
        if (mip != null) {
            mixed = true;
            dist = mip.instancesDistribution();
            if (mip.launchTemplate() != null) {
                lt = spec(mip.launchTemplate().launchTemplateSpecification());
                for (LaunchTemplateOverrides o : mip.launchTemplate().overrides()) {
                    if (o.instanceType() != null) {
                        overrides.add(o.instanceType());
                    }
                    if (o.instanceRequirements() != null) {
                        abs = true;
                    }
                }
            }
        } else {
            lt = spec(g.launchTemplate());
        }
        Map<String, String> tags = new LinkedHashMap<>();
        g.tags().forEach(t -> tags.put(t.key(), t.value() == null ? "" : t.value()));
        List<String> subnets = new ArrayList<>();
        if (g.vpcZoneIdentifier() != null && !g.vpcZoneIdentifier().isBlank()) {
            for (String s : g.vpcZoneIdentifier().split(",")) {
                if (!s.isBlank()) {
                    subnets.add(s.trim());
                }
            }
        }
        List<InstanceFacts> instances = new ArrayList<>();
        g.instances().forEach(i -> instances.add(new InstanceFacts(i.instanceId(), i.instanceType(),
                i.availabilityZone(), i.lifecycleStateAsString(), Boolean.TRUE.equals(i.protectedFromScaleIn()))));
        List<String> suspended = new ArrayList<>();
        g.suspendedProcesses().forEach(p -> suspended.add(p.processName()));
        return new AwsGroupFacts(
                region, g.autoScalingGroupName(), g.autoScalingGroupARN(), g.launchConfigurationName(), lt, mixed,
                overrides, abs,
                dist == null ? null : dist.onDemandBaseCapacity(),
                dist == null ? null : dist.onDemandPercentageAboveBaseCapacity(),
                dist == null ? null : dist.onDemandAllocationStrategy(),
                dist == null ? null : dist.spotAllocationStrategy(),
                dist == null ? null : dist.spotInstancePools(),
                dist == null ? null : dist.spotMaxPrice(),
                Boolean.TRUE.equals(g.capacityRebalance()),
                nz(g.minSize()), nz(g.maxSize()), nz(g.desiredCapacity()),
                g.desiredCapacityType(),
                g.healthCheckType(), g.healthCheckGracePeriod(), g.defaultInstanceWarmup(),
                subnets, new TreeSet<>(g.availabilityZones()), tags,
                g.targetGroupARNs(), g.loadBalancerNames(),
                Boolean.TRUE.equals(g.newInstancesProtectedFromScaleIn()),
                g.instanceMaintenancePolicy() == null ? null : g.instanceMaintenancePolicy().maxHealthyPercentage(),
                g.warmPoolConfiguration() != null,
                g.terminationPolicies(), suspended, instances);
    }

    private static LtSpec spec(LaunchTemplateSpecification s) {
        return s == null ? null : new LtSpec(s.launchTemplateId(), s.launchTemplateName(), s.version());
    }

    private static int nz(Integer i) {
        return i == null ? 0 : i;
    }
}
