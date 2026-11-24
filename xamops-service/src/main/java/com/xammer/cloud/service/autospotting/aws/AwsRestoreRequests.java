package com.xammer.cloud.service.autospotting.aws;

import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;
import software.amazon.awssdk.services.autoscaling.model.InstancesDistribution;
import software.amazon.awssdk.services.autoscaling.model.LaunchTemplate;
import software.amazon.awssdk.services.autoscaling.model.LaunchTemplateOverrides;
import software.amazon.awssdk.services.autoscaling.model.LaunchTemplateSpecification;
import software.amazon.awssdk.services.autoscaling.model.MixedInstancesPolicy;
import software.amazon.awssdk.services.autoscaling.model.UpdateAutoScalingGroupRequest;

import java.util.ArrayList;
import java.util.List;

/**
 * The two {@code UpdateAutoScalingGroup} requests a revert sends (design-aws.md §5.3 steps 1 and 3). Pure.
 * Owned fields only: never {@code DesiredCapacity}, {@code MinSize}, {@code MaxSize}, health checks, subnets,
 * zones or tags. There is no instance-refresh field on the request type at all.
 */
public final class AwsRestoreRequests {
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();
    private AwsRestoreRequests() {
    }

    /**
     * Step 1: raise the On-Demand share to 100 and leave the base as it is. Raising it is a purchase-option
     * change, so Auto Scaling replaces Spot with On-Demand launch-first (AC-REV-06). The other distribution
     * fields are sent as they are now, so the call changes nothing else. No launch template is sent.
     */
    public static UpdateAutoScalingGroupRequest drain(AutoScalingGroup live) {
        return drain(live, null);
    }

    /**
     * The same drain, carrying the maintenance policy XamOps owns when {@code config} has one (design-aws.md §6.4,
     * AD-08), so a fallback keeps launch-before-terminate even if the customer removed the policy since.
     */
    public static UpdateAutoScalingGroupRequest drain(AutoScalingGroup live, com.fasterxml.jackson.databind.JsonNode config) {
        AwsGroupFacts f = AwsGroupFacts.from("", live);
        InstancesDistribution.Builder d = InstancesDistribution.builder()
                .onDemandBaseCapacity(f.onDemandBase())
                .onDemandPercentageAboveBaseCapacity(100);
        if (f.onDemandAllocationStrategy() != null) {
            d.onDemandAllocationStrategy(f.onDemandAllocationStrategy());
        }
        if (f.spotAllocationStrategy() != null) {
            d.spotAllocationStrategy(f.spotAllocationStrategy());
        }
        if ("lowest-price".equals(f.spotAllocationStrategy()) && f.spotInstancePools() != null) {
            d.spotInstancePools(f.spotInstancePools());
        }
        if (f.spotMaxPrice() != null) {
            d.spotMaxPrice(f.spotMaxPrice());
        }
        UpdateAutoScalingGroupRequest.Builder request = UpdateAutoScalingGroupRequest.builder()
                .autoScalingGroupName(live.autoScalingGroupName())
                .mixedInstancesPolicy(MixedInstancesPolicy.builder().instancesDistribution(d.build()).build());
        AwsUpdateRequestBuilder.withMaintenancePolicy(request, config);
        return request.build();
    }

    /**
     * Step 3: the merged owned fields. A standalone target sends {@code LaunchTemplate} alone (which replaces the
     * policy in one call, V-AWS-10); a policy target sends the whole policy. {@code CapacityRebalance} is always
     * the snapshot's.
     */
    public static UpdateAutoScalingGroupRequest restore(String groupName, AwsThreeWayDiff.Target target) {
        UpdateAutoScalingGroupRequest.Builder b = UpdateAutoScalingGroupRequest.builder().autoScalingGroupName(groupName)
                .capacityRebalance(target.capacityRebalance());
        if (target.maintenancePolicy() != null) {
            AwsUpdateRequestBuilder.withMaintenancePolicy(b, JSON.createObjectNode()
                    .set(AwsMaintenancePolicy.KEY, target.maintenancePolicy()));
        }
        if (target.standalone()) {
            return b.launchTemplate(spec(target.template())).build();
        }
        List<LaunchTemplateOverrides> overrides = new ArrayList<>();
        for (AwsThreeWayDiff.Override o : target.overrides()) {
            overrides.add(override(o));
        }
        return b.mixedInstancesPolicy(MixedInstancesPolicy.builder()
                .launchTemplate(LaunchTemplate.builder().launchTemplateSpecification(spec(target.template()))
                        .overrides(overrides).build())
                .instancesDistribution(distribution(target.distribution())).build()).build();
    }

    /** The request fields that a revert must never send; empty means owned fields only. */
    public static List<String> forbidden(UpdateAutoScalingGroupRequest r) {
        List<String> bad = new ArrayList<>(AwsUpdateRequestBuilder.forbiddenFields(r));
        bad.remove("LaunchTemplate"); // a standalone restore sends it by design
        bad.remove("InstancesDistribution.SpotMaxPrice"); // restoring the original includes the customer's own
        return bad;
    }

    private static LaunchTemplateSpecification spec(AwsGroupFacts.LtSpec s) {
        LaunchTemplateSpecification.Builder b = LaunchTemplateSpecification.builder()
                .version(s.version() == null ? "$Default" : s.version());
        if (s.id() != null) {
            b.launchTemplateId(s.id());
        } else {
            b.launchTemplateName(s.name());
        }
        return b.build();
    }

    private static LaunchTemplateOverrides override(AwsThreeWayDiff.Override o) {
        if (o.sdk() != null) {
            return o.sdk();
        }
        LaunchTemplateOverrides.Builder b = LaunchTemplateOverrides.builder().instanceType(o.type());
        if (o.weight() != null) {
            b.weightedCapacity(o.weight());
        }
        if (o.spec() != null) {
            b.launchTemplateSpecification(spec(o.spec()));
        }
        return b.build();
    }

    private static InstancesDistribution distribution(AwsThreeWayDiff.Distribution d) {
        InstancesDistribution.Builder b = InstancesDistribution.builder();
        if (d == null) {
            return b.build();
        }
        if (d.onDemandAllocationStrategy() != null) {
            b.onDemandAllocationStrategy(d.onDemandAllocationStrategy());
        }
        if (d.base() != null) {
            b.onDemandBaseCapacity(d.base());
        }
        if (d.percentAboveBase() != null) {
            b.onDemandPercentageAboveBaseCapacity(d.percentAboveBase());
        }
        if (d.spotAllocationStrategy() != null) {
            b.spotAllocationStrategy(d.spotAllocationStrategy());
        }
        if ("lowest-price".equals(d.spotAllocationStrategy()) && d.spotInstancePools() != null) {
            b.spotInstancePools(d.spotInstancePools());
        }
        if (d.spotMaxPrice() != null) {
            b.spotMaxPrice(d.spotMaxPrice());
        }
        return b.build();
    }
}
