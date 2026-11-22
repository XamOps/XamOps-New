package com.xammer.cloud.service.autospotting.aws;

import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.OperationKind;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;
import software.amazon.awssdk.services.autoscaling.model.InstancesDistribution;
import software.amazon.awssdk.services.autoscaling.model.LaunchTemplate;
import software.amazon.awssdk.services.autoscaling.model.LaunchTemplateOverrides;
import software.amazon.awssdk.services.autoscaling.model.LaunchTemplateSpecification;
import software.amazon.awssdk.services.autoscaling.model.MixedInstancesPolicy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 23 (D-23-10): every AWS op kind the runner drives, started from the state production leaves the group in
 * (never pre-seeded into APPLYING), with the real runner, lifecycle table and the AWS fake on PostgreSQL. Asserts
 * the final op status, the final group state and the exact transition path. MIG_POLICY_UPDATE is GCP-only,
 * ACCEPT_BASELINE makes no lifecycle change, and ADOPT (UNMANAGED to LEGACY_UNCONFIRMED, no APPLYING start edge) is
 * driven from the discovery path, so none of them is in this AWS table.
 */
@Tag("containers")
class AwsOpKindLifecycleIT {

    private static final String DESIRED = "{\"shape\":\"MIXED_INSTANCES_POLICY\",\"launchTemplate\":{\"id\":\"lt-0abc\","
            + "\"version\":\"$Default\"},\"overrides\":[\"m5.large\",\"m5a.large\",\"m6i.large\"],"
            + "\"instancesDistribution\":{\"onDemandAllocationStrategy\":\"prioritized\",\"onDemandBaseCapacity\":1,"
            + "\"onDemandPercentageAboveBaseCapacity\":50,\"spotAllocationStrategy\":\"price-capacity-optimized\"},"
            + "\"capacityRebalance\":true}";

    private static AutoScalingGroup converted(int desired, int pct, int instances) {
        List<software.amazon.awssdk.services.autoscaling.model.Instance> list = new ArrayList<>();
        for (int i = 1; i <= instances; i++) {
            list.add(FakeAws.instance("i-" + i, false));
        }
        return FakeAws.asg("web-asg").launchTemplate((LaunchTemplateSpecification) null).capacityRebalance(true)
                .desiredCapacity(desired).maxSize(8).instances(list)
                .tags(FakeAws.tag(AwsGroupFacts.MARKER_TAG, "marker"))
                .mixedInstancesPolicy(MixedInstancesPolicy.builder().launchTemplate(LaunchTemplate.builder()
                        .launchTemplateSpecification(LaunchTemplateSpecification.builder().launchTemplateId("lt-0abc")
                                .version("$Default").build())
                        .overrides(List.of("m5.large", "m5a.large", "m6i.large").stream()
                                .map(t -> LaunchTemplateOverrides.builder().instanceType(t).build()).toList()).build())
                        .instancesDistribution(InstancesDistribution.builder().onDemandAllocationStrategy("prioritized")
                                .onDemandBaseCapacity(1).onDemandPercentageAboveBaseCapacity(pct)
                                .spotAllocationStrategy("price-capacity-optimized").build()).build()).build();
    }

    /** One table row: how to build the harness, how many Spot instances the fake reports, and the expected path. */
    private record Case(String name, Supplier<AwsOpsHarness> harness, int spot, String status, String finalState,
                        List<String> path, java.util.function.Consumer<AwsOpsHarness> afterFirstRun) {
        Case(String name, Supplier<AwsOpsHarness> harness, int spot, String status, String finalState, List<String> path) {
            this(name, harness, spot, status, finalState, path, h -> { });
        }
    }

    private static List<Case> table() {
        return List.of(
                new Case("ENABLE from PLANNED", () -> new AwsOpsHarness(FakeAws.asg("web-asg").build(),
                        OperationKind.ENABLE, GroupState.PLANNED, GroupState.UNMANAGED, "conservative"), 1,
                        "SUCCEEDED", "ACTIVE", List.of("PLANNED>APPLYING", "APPLYING>ACTIVE")),
                new Case("PROFILE_CHANGE from ACTIVE", () -> new AwsOpsHarness(converted(4, 50, 4),
                        OperationKind.PROFILE_CHANGE, GroupState.ACTIVE, GroupState.ACTIVE, "aggressive"), 3,
                        "SUCCEEDED", "ACTIVE", List.of("ACTIVE>APPLYING", "APPLYING>ACTIVE")),
                new Case("CONFIG_APPLY from ACTIVE", () -> new AwsOpsHarness(converted(4, 50, 4),
                        OperationKind.CONFIG_APPLY, GroupState.ACTIVE, GroupState.ACTIVE, "aggressive"), 3,
                        "SUCCEEDED", "ACTIVE", List.of("ACTIVE>APPLYING", "APPLYING>ACTIVE")),
                // A real enter starts from a group that is short (desired 5, 4 instances), as in AwsFallbackIT.
                new Case("FALLBACK_ENTER from ACTIVE", () -> new AwsOpsHarness(converted(5, 50, 4), GroupState.ACTIVE,
                        "MANAGED", DESIRED, OperationKind.FALLBACK_ENTER, null, null), 0,
                        "SUCCEEDED", "FALLBACK", List.of("ACTIVE>FALLBACK"),
                        // After the switch to On-Demand, AWS fills the group (otherwise it is DEGRADED by design).
                        h -> h.f.replaceGroup(h.f.group().toBuilder().instances(java.util.stream.IntStream.rangeClosed(1, 5)
                                .mapToObj(i -> FakeAws.instance("i-" + i, false)).toList()).build())),
                new Case("FALLBACK_EXIT from FALLBACK", () -> new AwsOpsHarness(converted(4, 100, 4),
                        GroupState.FALLBACK, "FALLBACK", DESIRED, OperationKind.FALLBACK_EXIT, null, null),
                        AwsProfiles.spotCount(4, 1, 50), "SUCCEEDED", "ACTIVE",
                        List.of("FALLBACK>APPLYING", "APPLYING>ACTIVE")),
                new Case("REVERT from ACTIVE", () -> new AwsOpsHarness(converted(4, 50, 4),
                        FakeAws.asg("web-asg").build(), GroupState.ACTIVE, DESIRED), 0,
                        "SUCCEEDED", "REVERTED", List.of("ACTIVE>REVERTING", "REVERTING>REVERTED")));
    }

    @Test
    void everyOpKindRunsFromItsProductionStartStateToItsFinalState() {
        List<String> report = new ArrayList<>();
        List<String> mismatches = new ArrayList<>();
        for (Case c : table()) {
            try (AwsOpsHarness h = c.harness().get()) {
                for (int i = 1; i <= c.spot(); i++) {
                    h.f.spotInstances.add("i-" + (5 - i));
                }
                h.runner.runClaimed(h.lease, h.f.opId);
                c.afterFirstRun().accept(h);
                for (int round = 0; round < 60 && !terminal(h.opStatus()); round++) {
                    h.f.clock.advance(Duration.ofMinutes(1));
                    // A revert drains Spot before it restores: the fake's Spot instances leave as AWS would.
                    if (c.name().startsWith("REVERT")) {
                        h.f.spotInstances.clear();
                    }
                    h.claimAndRun();
                }
                List<String> path = h.jdbc.queryForList("SELECT from_state || '>' || to_state FROM spot_group_transition"
                        + " WHERE group_id = ? ORDER BY id", String.class, h.f.groupId);
                String row = c.name() + ": status=" + h.opStatus() + " error=" + h.opError() + " final="
                        + h.groupState() + " path=" + path;
                report.add(row);
                if (!c.status().equals(h.opStatus()) || !c.finalState().equals(h.groupState()) || !c.path().equals(path)) {
                    mismatches.add(row + " | expected status=" + c.status() + " final=" + c.finalState()
                            + " path=" + c.path());
                }
            }
        }
        report.forEach(System.out::println);
        assertThat(mismatches).as(String.join("\n", report)).isEmpty();
    }

    /**
     * A drift re-apply from PAUSED(DRIFT) goes PAUSED -> APPLYING -> PAUSED and must keep pause_reason and
     * paused_from_state on both rows, or the reconciler loses the drift pause (review finding #2).
     */
    @Test
    void aDriftPausedGroupKeepsItsPauseColumnsThroughAConfigApplyAndAProfileChange() {
        List<String> problems = new ArrayList<>();
        for (OperationKind kind : List.of(OperationKind.CONFIG_APPLY, OperationKind.PROFILE_CHANGE)) {
            try (AwsOpsHarness h = new AwsOpsHarness(converted(4, 50, 4), kind, GroupState.PAUSED, GroupState.PAUSED,
                    "aggressive")) {
                h.jdbc.update("UPDATE spot_group SET pause_reason = 'DRIFT', paused_from_state = 'ACTIVE' WHERE id = ?",
                        h.f.groupId);
                for (int i = 1; i <= 3; i++) {
                    h.f.spotInstances.add("i-" + (5 - i));
                }
                h.runner.runClaimed(h.lease, h.f.opId);
                for (int round = 0; round < 60 && !terminal(h.opStatus()); round++) {
                    midFlightColumns(h, kind, problems);
                    h.f.clock.advance(Duration.ofMinutes(1));
                    h.claimAndRun();
                }
                midFlightColumns(h, kind, problems);
                List<String> path = h.jdbc.queryForList("SELECT from_state || '>' || to_state FROM spot_group_transition"
                        + " WHERE group_id = ? ORDER BY id", String.class, h.f.groupId);
                String row = kind + ": status=" + h.opStatus() + " error=" + h.opError() + " final=" + h.groupState()
                        + " path=" + path + " pause=" + pauseColumns(h);
                if (!"SUCCEEDED".equals(h.opStatus()) || !"PAUSED".equals(h.groupState())
                        || !List.of("PAUSED>APPLYING", "APPLYING>PAUSED").equals(path)
                        || !"DRIFT/ACTIVE".equals(pauseColumns(h))) {
                    problems.add(row);
                }
            }
        }
        assertThat(problems).isEmpty();
    }

    private static void midFlightColumns(AwsOpsHarness h, OperationKind kind, List<String> problems) {
        if ("APPLYING".equals(h.groupState()) && !"DRIFT/ACTIVE".equals(pauseColumns(h))) {
            problems.add(kind + " lost its pause columns while APPLYING: " + pauseColumns(h));
        }
    }

    private static String pauseColumns(AwsOpsHarness h) {
        return h.jdbc.queryForObject("SELECT pause_reason || '/' || paused_from_state FROM spot_group WHERE id = ?",
                String.class, h.f.groupId);
    }

    private static boolean terminal(String status) {
        return "SUCCEEDED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status);
    }
}
