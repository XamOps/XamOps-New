package com.xammer.cloud.service.autospotting.aws;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.xammer.cloud.service.autospotting.spi.ErrorKind;
import com.xammer.cloud.service.autospotting.spi.GroupStatus;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;
import software.amazon.awssdk.services.autoscaling.model.DescribeAutoScalingGroupsRequest;
import software.amazon.awssdk.services.autoscaling.model.DescribeAutoScalingGroupsResponse;
import software.amazon.awssdk.services.autoscaling.model.InstanceRefresh;
import software.amazon.awssdk.services.autoscaling.model.Activity;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesResponse;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.Reservation;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * {@code getStatus} for AWS (design-aws.md §2.1, NFR-04): at most four calls per group per sweep.
 *
 * <ol>
 *   <li>{@code DescribeAutoScalingGroups} (by name);</li>
 *   <li>{@code DescribeScalingActivities} (max 50);</li>
 *   <li>{@code DescribeInstanceRefreshes};</li>
 *   <li>{@code DescribeInstances} only when the Spot/On-Demand split is needed and not cached.</li>
 * </ol>
 *
 * <p>A group the describe does not return is {@link GroupStatus#notFound()} (feeds the DR-7 GONE
 * check). A permission failure is thrown as an {@link AwsReadException} with kind {@code PERMISSION},
 * so it is never mistaken for NOT_FOUND (§9.2). Read only.
 */
@Component
public class AwsGroupStatusReader {

    /** Instance refresh statuses that mean a refresh is running (DR-6). */
    static final Set<String> ACTIVE_REFRESH = Set.of("Pending", "InProgress", "Cancelling", "RollbackInProgress");
    static final int MAX_ACTIVITIES = 50;
    static final int MAX_INSTANCE_IDS = 500;

    /** The Spot/On-Demand split, cached briefly so a sweep rarely calls DescribeInstances. */
    private final Cache<String, int[]> splitCache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofMinutes(5))
            .build();

    /** Read the live status of {@code name} through {@code view}. */
    public GroupStatus read(AwsReadOnlyView view, String name) {
        return read(view, name, false);
    }

    /** As {@link #read(AwsReadOnlyView, String)}, with the maintenance policy in the fingerprint when XamOps owns it. */
    public GroupStatus read(AwsReadOnlyView view, String name, boolean ownsMaintenancePolicy) {
        Optional<AutoScalingGroup> found = describeGroup(view, name);
        if (found.isEmpty()) {
            return GroupStatus.notFound();
        }
        return read(view, found.get(), ownsMaintenancePolicy);
    }

    /** Read the live status given a group already described (the caller made call 1). */
    public GroupStatus read(AwsReadOnlyView view, AutoScalingGroup group) {
        return read(view, group, false);
    }

    /** As {@link #read(AwsReadOnlyView, AutoScalingGroup)}; the fingerprint includes the maintenance policy when XamOps owns it. */
    public GroupStatus read(AwsReadOnlyView view, AutoScalingGroup group, boolean ownsMaintenancePolicy) {
        String name = group.autoScalingGroupName();
        AwsGroupFacts facts = AwsGroupFacts.from(view.region(), group);
        List<Activity> activities = AwsErrors.read("DescribeScalingActivities", () ->
                view.autoScaling().describeScalingActivities(r -> r.autoScalingGroupName(name)
                        .maxRecords(MAX_ACTIVITIES)).activities());
        List<InstanceRefresh> refreshes = AwsErrors.read("DescribeInstanceRefreshes", () ->
                view.autoScaling().describeInstanceRefreshes(r -> r.autoScalingGroupName(name))
                        .instanceRefreshes());
        boolean refreshActive = refreshes.stream().anyMatch(r -> ACTIVE_REFRESH.contains(r.statusAsString()));
        long failed = activities.stream().filter(a -> "Failed".equals(a.statusCodeAsString())).count();
        int[] split = split(view, facts);
        long inService = facts.instances().stream().filter(i -> "InService".equals(i.lifecycleState())).count();
        long healthy = group.instances().stream()
                .filter(i -> "InService".equals(i.lifecycleStateAsString()) && "Healthy".equals(i.healthStatus()))
                .count();
        // The Spot-failure streak the reconciler's shortage rule reads (design-aws.md §2.5): the run of
        // Spot-capacity launch failures since the last successful launch (activities are newest first).
        java.time.Instant lastSpotFailure = null;
        java.time.Instant streakSince = null;
        for (Activity a : activities) {
            if ("Successful".equals(a.statusCodeAsString()) && AwsRolloutObserver.isLaunch(a)) {
                break;
            }
            if (AwsRolloutObserver.isSpotFailure(a) && a.startTime() != null) {
                if (lastSpotFailure == null || a.startTime().isAfter(lastSpotFailure)) {
                    lastSpotFailure = a.startTime();
                }
                if (streakSince == null || a.startTime().isBefore(streakSince)) {
                    streakSince = a.startTime();
                }
            }
        }
        // Fallback signals (design-aws.md §6.1): the launch failures by class and the unhealthy instances by type.
        // Times only; the rules compare them to their windows with the sweep's clock.
        StringBuilder spotTimes = new StringBuilder();
        java.time.Instant quotaAt = null;
        java.time.Instant odFailureAt = null;
        int listed = 0;
        for (Activity a : activities) {
            if (!"Failed".equals(a.statusCodeAsString()) || !AwsRolloutObserver.isLaunch(a) || a.startTime() == null) {
                continue;
            }
            AwsLaunchFailureClassifier.Result r = AwsLaunchFailureClassifier.classify(a.statusMessage());
            if (r.spot() && listed < MAX_ACTIVITIES) {
                spotTimes.append(listed++ == 0 ? "" : ",").append('"').append(a.startTime()).append('"');
            }
            if (r.quota() && (quotaAt == null || a.startTime().isAfter(quotaAt))) {
                quotaAt = a.startTime();
            }
            if (r.failureClass() == AwsSpotFailureMatcher.FailureClass.ON_DEMAND
                    && (odFailureAt == null || a.startTime().isAfter(odFailureAt))) {
                odFailureAt = a.startTime();
            }
        }
        java.util.Map<String, Integer> unhealthyByType = new java.util.TreeMap<>();
        for (software.amazon.awssdk.services.autoscaling.model.Instance i : group.instances()) {
            if ("InService".equals(i.lifecycleStateAsString()) && i.healthStatus() != null
                    && !"Healthy".equals(i.healthStatus()) && i.instanceType() != null) {
                unhealthyByType.merge(i.instanceType(), 1, Integer::sum);
            }
        }
        StringBuilder unhealthyJson = new StringBuilder("{");
        unhealthyByType.forEach((t, n) -> unhealthyJson.append(unhealthyJson.length() > 1 ? "," : "")
                .append('"').append(t.replace("\"", "")).append("\":").append(n));
        unhealthyJson.append('}');
        // The instance ids, so the reconciler can keep spot_group_instance current (design-aws.md §9.7).
        StringBuilder instanceIds = new StringBuilder();
        int listedIds = 0;
        for (AwsGroupFacts.InstanceFacts i : facts.instances()) {
            if (i.id() != null && i.id().startsWith("i-") && listedIds < MAX_INSTANCE_IDS) {
                instanceIds.append(listedIds++ == 0 ? "" : ",").append('"').append(i.id().replace("\"", "")).append('"');
            }
        }
        String health = "{\"inService\":" + inService
                + ",\"instances\":" + facts.instances().size()
                + ",\"recentFailedActivities\":" + failed
                + ",\"refreshInProgress\":" + refreshActive
                + ",\"healthyInService\":" + healthy
                + ",\"lastSpotFailureAt\":" + (lastSpotFailure == null ? "null" : "\"" + lastSpotFailure + "\"")
                + ",\"spotFailureStreakSince\":" + (streakSince == null ? "null" : "\"" + streakSince + "\"")
                + ",\"spotFailureTimes\":[" + spotTimes + "]"
                + ",\"quotaFailureAt\":" + (quotaAt == null ? "null" : "\"" + quotaAt + "\"")
                + ",\"onDemandFailureAt\":" + (odFailureAt == null ? "null" : "\"" + odFailureAt + "\"")
                + ",\"unhealthyByType\":" + unhealthyJson
                + ",\"instanceIds\":[" + instanceIds + "]"
                + "}";
        String capacity = "{\"min\":" + facts.min() + ",\"max\":" + facts.max()
                + ",\"desired\":" + facts.desired()
                + ",\"spot\":" + split[1] + ",\"onDemand\":" + split[0]
                + ",\"mixed\":" + facts.mixedInstancesPolicy()
                + ",\"odPercentAboveBase\":" + facts.onDemandPercentageAboveBase() + "}";
        return new GroupStatus(true, AwsFingerprint.of(facts, ownsMaintenancePolicy), health, capacity, refreshActive);
    }

    /** Call 1: the group by name; empty when AWS returns nothing, an {@link AwsReadException} on failure. */
    public Optional<AutoScalingGroup> describeGroup(AwsReadOnlyView view, String name) {
        DescribeAutoScalingGroupsResponse resp = AwsErrors.read("DescribeAutoScalingGroups", () ->
                view.autoScaling().describeAutoScalingGroups(DescribeAutoScalingGroupsRequest.builder()
                        .autoScalingGroupNames(name).build()));
        return resp.autoScalingGroups().stream()
                .filter(g -> name.equals(g.autoScalingGroupName()))
                .findFirst();
    }

    /** {@code [onDemand, spot]} for the group's instances; cached so the common sweep skips call 4. */
    private int[] split(AwsReadOnlyView view, AwsGroupFacts facts) {
        if (facts.instances().isEmpty()) {
            return new int[] {0, 0};
        }
        String key = facts.arn() + "#" + facts.instances().stream().map(AwsGroupFacts.InstanceFacts::id)
                .sorted().toList().hashCode();
        int[] cached = splitCache.getIfPresent(key);
        if (cached != null) {
            return cached;
        }
        List<String> ids = facts.instances().stream().map(AwsGroupFacts.InstanceFacts::id).toList();
        int spot = 0;
        int total = 0;
        String token = null;
        do {
            final String next = token;
            DescribeInstancesResponse resp = AwsErrors.read("DescribeInstances", () ->
                    view.ec2().describeInstances(DescribeInstancesRequest.builder()
                            .filters(Filter.builder().name("instance-id").values(ids).build())
                            .nextToken(next).build()));
            for (Reservation r : resp.reservations()) {
                for (Instance i : r.instances()) {
                    total++;
                    if ("spot".equals(i.instanceLifecycleAsString())) {
                        spot++;
                    }
                }
            }
            token = resp.nextToken();
        } while (token != null);
        int[] out = new int[] {total - spot, spot};
        splitCache.put(key, out);
        return out;
    }

    /** Whether {@code kind} marks the group as missing rather than unreadable. */
    public static boolean isNotFound(ErrorKind kind) {
        return kind == ErrorKind.NOT_FOUND;
    }
    /**
     * The Spot placement score (1 to 10) of the group's instance types in its region for {@code desired}
     * instances (design-aws.md §6.5), or null when it cannot be read (unavailable, rate-limited or not
     * permitted), in which case the exit is time-based. One read-only EC2 call; never throws.
     */
    public Integer placementScore(AwsReadOnlyView view, String name, int desired) {
        try {
            Optional<AutoScalingGroup> g = describeGroup(view, name);
            if (g.isEmpty() || desired < 1) {
                return null;
            }
            AwsGroupFacts facts = AwsGroupFacts.from(view.region(), g.get());
            List<String> types = facts.overrideTypes().isEmpty() && !facts.instances().isEmpty()
                    ? List.of(facts.instances().get(0).type()) : facts.overrideTypes();
            if (types.isEmpty()) {
                return null;
            }
            software.amazon.awssdk.services.ec2.model.GetSpotPlacementScoresResponse r = view.ec2()
                    .getSpotPlacementScores(x -> x.instanceTypes(types).targetCapacity(desired)
                            .regionNames(view.region()).singleAvailabilityZone(false).maxResults(10));
            return r.spotPlacementScores().stream().map(sc -> sc.score()).filter(java.util.Objects::nonNull)
                    .max(Integer::compareTo).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
