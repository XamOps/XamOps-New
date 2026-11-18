package com.xammer.cloud.service.autospotting.aws;

import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.autoscaling.model.Activity;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;
import software.amazon.awssdk.services.autoscaling.model.DescribeAutoScalingGroupsRequest;
import software.amazon.awssdk.services.autoscaling.model.DescribeScalingActivitiesRequest;
import software.amazon.awssdk.services.autoscaling.model.DescribeTrafficSourcesRequest;
import software.amazon.awssdk.services.autoscaling.model.Instance;
import software.amazon.awssdk.services.autoscaling.model.TrafficSourceState;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesResponse;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.Reservation;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeTargetHealthRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetHealthDescription;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reads what one {@code WAIT_ROLLOUT} poll needs (design-aws.md §2.5), read-only, through an
 * {@link AwsReadOnlyView}:
 * {@code DescribeAutoScalingGroups}, {@code DescribeScalingActivities} (max 50), {@code DescribeInstances}
 * (Spot lifecycle, since the group describe has no market type), one {@code DescribeTargetHealth} per
 * attached target group, and {@code DescribeTrafficSources} for classic load balancers.
 *
 * <p>An instance counts as healthy in service only if {@code LifecycleState=InService},
 * {@code HealthStatus=Healthy}, {@code healthy} in every attached target group, and every attached
 * classic load balancer is {@code InService}.
 */
@Component
public class AwsRolloutObserver {

    static final int MAX_ACTIVITIES = 50;

    /** One poll's facts. {@code spotFailureStreakSince} is the start of the current Spot-failure streak, or null. */
    public record Snapshot(AutoScalingGroup group, int desired, int spotNow, int healthyInService,
                           Instant lastSpotFailureAt, Instant spotFailureStreakSince, List<Activity> activities) {
    }

    /** Observe {@code groupName}; an {@link AwsReadException} when a read fails, empty when the group is gone. */
    public Optional<Snapshot> observe(AwsReadOnlyView view, String groupName) {
        AutoScalingGroup group = AwsErrors.read("DescribeAutoScalingGroups", () -> view.autoScaling()
                .describeAutoScalingGroups(DescribeAutoScalingGroupsRequest.builder()
                        .autoScalingGroupNames(groupName).build()).autoScalingGroups().stream()
                .filter(g -> groupName.equals(g.autoScalingGroupName())).findFirst().orElse(null));
        if (group == null) {
            return Optional.empty();
        }
        List<Activity> activities = AwsErrors.read("DescribeScalingActivities", () -> view.autoScaling()
                .describeScalingActivities(DescribeScalingActivitiesRequest.builder()
                        .autoScalingGroupName(groupName).maxRecords(MAX_ACTIVITIES).build()).activities());

        List<String> inServiceIds = new ArrayList<>();
        for (Instance i : group.instances()) {
            if ("InService".equals(i.lifecycleStateAsString())) {
                inServiceIds.add(i.instanceId());
            }
        }
        int spotNow = spotCount(view, inServiceIds);
        int healthy = healthyCount(view, group, inServiceIds);
        Instant last = null;
        Instant streak = null;
        // Activities come newest first. The streak is the run of Spot failures since the last success.
        for (Activity a : activities) {
            if ("Successful".equals(a.statusCodeAsString()) && isLaunch(a)) {
                break;
            }
            if (isSpotFailure(a) && a.startTime() != null) {
                if (last == null || a.startTime().isAfter(last)) {
                    last = a.startTime();
                }
                if (streak == null || a.startTime().isBefore(streak)) {
                    streak = a.startTime();
                }
            }
        }
        return Optional.of(new Snapshot(group, group.desiredCapacity() == null ? 0 : group.desiredCapacity(),
                spotNow, healthy, last, streak, activities));
    }

    static boolean isLaunch(Activity a) {
        String d = a.description() == null ? "" : a.description();
        return d.startsWith("Launching");
    }

    static boolean isSpotFailure(Activity a) {
        if (!"Failed".equals(a.statusCodeAsString()) || !isLaunch(a)) {
            return false;
        }
        return AwsSpotFailureMatcher.isSpotFailure(AwsSpotFailureMatcher.classify(a.statusMessage(), a.description()));
    }

    private int spotCount(AwsReadOnlyView view, List<String> ids) {
        if (ids.isEmpty()) {
            return 0;
        }
        int spot = 0;
        String token = null;
        do {
            final String next = token;
            DescribeInstancesResponse resp = AwsErrors.read("DescribeInstances", () -> view.ec2()
                    .describeInstances(DescribeInstancesRequest.builder()
                            .filters(Filter.builder().name("instance-id").values(ids).build())
                            .nextToken(next).build()));
            for (Reservation r : resp.reservations()) {
                for (software.amazon.awssdk.services.ec2.model.Instance i : r.instances()) {
                    if ("spot".equals(i.instanceLifecycleAsString())) {
                        spot++;
                    }
                }
            }
            token = resp.nextToken();
        } while (token != null);
        return spot;
    }

    private int healthyCount(AwsReadOnlyView view, AutoScalingGroup group, List<String> inServiceIds) {
        Set<String> candidates = new HashSet<>();
        for (Instance i : group.instances()) {
            if (inServiceIds.contains(i.instanceId()) && "Healthy".equals(i.healthStatus())) {
                candidates.add(i.instanceId());
            }
        }
        for (String tg : group.targetGroupARNs()) {
            Map<String, String> states = new HashMap<>();
            List<TargetHealthDescription> descriptions = AwsErrors.read("DescribeTargetHealth", () -> view.elbv2()
                    .describeTargetHealth(DescribeTargetHealthRequest.builder().targetGroupArn(tg).build())
                    .targetHealthDescriptions());
            for (TargetHealthDescription d : descriptions) {
                states.put(d.target().id(), d.targetHealth() == null ? null : d.targetHealth().stateAsString());
            }
            candidates.removeIf(id -> !"healthy".equals(states.get(id)));
        }
        if (!group.loadBalancerNames().isEmpty()) {
            List<TrafficSourceState> sources = AwsErrors.read("DescribeTrafficSources", () -> view.autoScaling()
                    .describeTrafficSources(DescribeTrafficSourcesRequest.builder()
                            .autoScalingGroupName(group.autoScalingGroupName()).trafficSourceType("elb").build())
                    .trafficSources());
            boolean allIn = sources.stream().allMatch(s -> "InService".equals(s.state()));
            if (!allIn) {
                candidates.clear();
            }
        }
        return candidates.size();
    }
}
