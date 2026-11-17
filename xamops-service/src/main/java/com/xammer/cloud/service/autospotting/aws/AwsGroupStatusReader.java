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

    /** The Spot/On-Demand split, cached briefly so a sweep rarely calls DescribeInstances. */
    private final Cache<String, int[]> splitCache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofMinutes(5))
            .build();

    /** Read the live status of {@code name} through {@code view}. */
    public GroupStatus read(AwsReadOnlyView view, String name) {
        Optional<AutoScalingGroup> found = describeGroup(view, name);
        if (found.isEmpty()) {
            return GroupStatus.notFound();
        }
        return read(view, found.get());
    }

    /** Read the live status given a group already described (the caller made call 1). */
    public GroupStatus read(AwsReadOnlyView view, AutoScalingGroup group) {
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
        String health = "{\"inService\":" + inService
                + ",\"instances\":" + facts.instances().size()
                + ",\"recentFailedActivities\":" + failed
                + ",\"refreshInProgress\":" + refreshActive + "}";
        String capacity = "{\"min\":" + facts.min() + ",\"max\":" + facts.max()
                + ",\"desired\":" + facts.desired()
                + ",\"spot\":" + split[1] + ",\"onDemand\":" + split[0] + "}";
        return new GroupStatus(true, AwsFingerprint.of(facts), health, capacity, refreshActive);
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
}
