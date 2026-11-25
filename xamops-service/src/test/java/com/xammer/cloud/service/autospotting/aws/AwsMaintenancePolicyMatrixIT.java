package com.xammer.cloud.service.autospotting.aws;

import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.OperationKind;
import com.xammer.cloud.domain.autospotting.Provider;
import com.xammer.cloud.service.autospotting.spi.GroupRef;
import com.xammer.cloud.service.autospotting.spi.PlanOptions;
import com.xammer.cloud.service.autospotting.spi.PlanRequest;
import com.xammer.cloud.service.autospotting.spi.Profile;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.autoscaling.model.Activity;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;
import software.amazon.awssdk.services.autoscaling.model.DescribeScalingActivitiesRequest;
import software.amazon.awssdk.services.autoscaling.model.DescribeScalingActivitiesResponse;
import software.amazon.awssdk.services.autoscaling.model.InstanceMaintenancePolicy;
import software.amazon.awssdk.services.autoscaling.model.InstancesDistribution;
import software.amazon.awssdk.services.autoscaling.model.LaunchTemplate;
import software.amazon.awssdk.services.autoscaling.model.LaunchTemplateOverrides;
import software.amazon.awssdk.services.autoscaling.model.LaunchTemplateSpecification;
import software.amazon.awssdk.services.autoscaling.model.MixedInstancesPolicy;
import software.amazon.awssdk.services.autoscaling.model.UpdateAutoScalingGroupRequest;
import software.amazon.awssdk.services.autoscaling.model.UpdateAutoScalingGroupResponse;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * Every operation that writes, verifies or restores the instance maintenance policy (design-aws.md AD-08, D-23-09),
 * driven through the real ConfirmService (where the op is confirmed), the real OperationRunner and the AWS fake, over
 * op kind x starting policy x outcome. Each row asserts the op outcome and the policy the fake group ends with.
 *
 * <p>Starting policies: ABSENT (none), CUSTOMER (90/150, the customer's own, never overwritten), OWNED (XamOps
 * 100/200 recorded in desired_config), EDITED (owned, then the customer set it to 100/100).
 * Outcomes: SUCCESS, FAILURE (a Spot shortage after the first mutation, so the op compensates), UNWRAP (AWS refuses
 * the revert's unwrap, so the single-override fallback runs).
 */
@Tag("containers")
class AwsMaintenancePolicyMatrixIT {

    enum Start { ABSENT, CUSTOMER, OWNED, EDITED, REMOVED }

    private static final String OWNED_JSON = ",\"instanceMaintenancePolicy\":{\"owned\":true,\"min\":100,\"max\":200}";
    private static final List<String> TYPES = List.of("m5.large", "m5a.large", "m6i.large");

    private static InstanceMaintenancePolicy imp(int min, int max) {
        return InstanceMaintenancePolicy.builder().minHealthyPercentage(min).maxHealthyPercentage(max).build();
    }

    /** The policy a group starts with for {@code s}. */
    private static InstanceMaintenancePolicy livePolicy(Start s) {
        return switch (s) {
            case ABSENT, REMOVED -> null;
            case CUSTOMER -> imp(90, 150);
            case OWNED -> imp(100, 200);
            case EDITED -> imp(100, 100);
        };
    }

    private static boolean ownedInDesired(Start s) {
        return s == Start.OWNED || s == Start.EDITED || s == Start.REMOVED;
    }

    private static String desired(int pct, boolean owned) {
        return "{\"shape\":\"MIXED_INSTANCES_POLICY\",\"launchTemplate\":{\"id\":\"lt-0abc\",\"version\":\"$Default\"},"
                + "\"overrides\":" + TYPES.stream().map(t -> "\"" + t + "\"").toList()
                + ",\"instancesDistribution\":{\"onDemandAllocationStrategy\":\"prioritized\",\"onDemandBaseCapacity\":1,"
                + "\"onDemandPercentageAboveBaseCapacity\":" + pct + ",\"spotAllocationStrategy\":\"price-capacity-optimized\"},"
                + "\"capacityRebalance\":true" + (owned ? OWNED_JSON : "") + "}";
    }

    /** A converted group (1 On-Demand base, {@code pct} above it) of {@code desired}, with {@code instances}. */
    private static AutoScalingGroup converted(InstanceMaintenancePolicy policy, int pct, int desired, int instances) {
        List<software.amazon.awssdk.services.autoscaling.model.Instance> list = new ArrayList<>();
        for (int i = 1; i <= instances; i++) {
            list.add(FakeAws.instance("i-" + i, false));
        }
        AutoScalingGroup.Builder b = FakeAws.asg("web-asg").launchTemplate((LaunchTemplateSpecification) null)
                .capacityRebalance(true).desiredCapacity(desired).maxSize(8).instances(list)
                .tags(FakeAws.tag(AwsGroupFacts.MARKER_TAG, "marker"))
                .mixedInstancesPolicy(MixedInstancesPolicy.builder().launchTemplate(LaunchTemplate.builder()
                        .launchTemplateSpecification(LaunchTemplateSpecification.builder().launchTemplateId("lt-0abc")
                                .version("$Default").build())
                        .overrides(TYPES.stream().map(t -> LaunchTemplateOverrides.builder().instanceType(t).build())
                                .toList()).build())
                        .instancesDistribution(InstancesDistribution.builder().onDemandAllocationStrategy("prioritized")
                                .onDemandBaseCapacity(1).onDemandPercentageAboveBaseCapacity(pct)
                                .spotAllocationStrategy("price-capacity-optimized").build()).build());
        if (policy != null) {
            b.instanceMaintenancePolicy(policy);
        }
        return b.build();
    }

    private static AutoScalingGroup withInstances(AutoScalingGroup g, int n) {
        List<software.amazon.awssdk.services.autoscaling.model.Instance> list = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            list.add(FakeAws.instance("i-" + i, false));
        }
        return g.toBuilder().instances(list).build();
    }

    /** Spot-capacity launch failures that began 11 minutes ago, so the shortage window has already passed. */
    private static void spotShortage(AwsOpsHarness h) {
        doAnswer(inv -> {
            java.time.Instant now = h.f.clock.instant();
            Activity recent = Activity.builder().activityId("fail-2").statusCode("Failed")
                    .description("Launching a new EC2 instance").startTime(now.minusSeconds(60))
                    .statusMessage("There is no Spot capacity available that matches your request.").build();
            Activity first = Activity.builder().activityId("fail-1").statusCode("Failed")
                    .description("Launching a new EC2 instance").startTime(now.minus(Duration.ofMinutes(11)))
                    .statusMessage("There is no Spot capacity available that matches your request.").build();
            return DescribeScalingActivitiesResponse.builder().activities(recent, first).build();
        }).when(h.f.aws.asg).describeScalingActivities(any(DescribeScalingActivitiesRequest.class));
    }

    private static boolean ended(String status) {
        return "SUCCEEDED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status);
    }

    private static String status(AwsOpsHarness h, UUID op) {
        return h.jdbc.queryForObject("SELECT status FROM spot_operation WHERE id = ?", String.class, op);
    }

    private static String phase(AwsOpsHarness h, UUID op) {
        return h.jdbc.queryForObject("SELECT phase FROM spot_operation WHERE id = ?", String.class, op);
    }

    /** Run {@code op} until it ends, one minute apart. */
    private static String runToEnd(AwsOpsHarness h, UUID op) {
        for (int round = 0; round < 60 && !ended(status(h, op)); round++) {
            if (round > 0) {
                h.f.clock.advance(Duration.ofMinutes(1));
            }
            h.store.claimForRun(op);
            h.runner.runClaimed(h.lease, op);
        }
        return status(h, op);
    }

    /** Run {@code op} until it compensates, give the group its capacity back, run to the end. */
    private static String runThroughCompensation(AwsOpsHarness h, UUID op) {
        for (int round = 0; round < 60 && !"COMPENSATING".equals(phase(h, op)) && !ended(status(h, op)); round++) {
            if (round > 0) {
                h.f.clock.advance(Duration.ofMinutes(1));
            }
            h.store.claimForRun(op);
            h.runner.runClaimed(h.lease, op);
        }
        assertThat(phase(h, op)).as("the shortage made the op compensate").isEqualTo("COMPENSATING");
        h.f.replaceGroup(withInstances(h.f.group(), 5));
        return runToEnd(h, op);
    }

    private static void assertPolicy(AwsOpsHarness h, InstanceMaintenancePolicy expected) {
        InstanceMaintenancePolicy live = h.f.group().instanceMaintenancePolicy();
        if (expected == null) {
            assertThat(live).as("the live policy is absent").isNull();
        } else {
            assertThat(live).as("the live policy").isNotNull();
            assertThat(live.minHealthyPercentage()).as("live min").isEqualTo(expected.minHealthyPercentage());
            assertThat(live.maxHealthyPercentage()).as("live max").isEqualTo(expected.maxHealthyPercentage());
        }
    }

    private static PlanRequest request(OperationKind kind, String desired) {
        return new PlanRequest(new GroupRef(Provider.AWS, 7L, FakeAws.REGION + "/web-asg", null), kind,
                new Profile("aggressive", null), new PlanOptions(null, desired, true));
    }

    @FunctionalInterface
    interface Body {
        void run() throws Exception;
    }

    record Row(String name, Body body) {
        @Override
        public String toString() {
            return name;
        }
    }

    private static Row row(String name, Body body) {
        return new Row(name, body);
    }

    static Stream<Arguments> rows() {
        List<Row> rows = new ArrayList<>();
        // CONFIG_APPLY: through ConfirmService -> OperationRunner. The converted group runs 50% Spot, the plan wants more.
        for (Start s : Start.values()) {
            InstanceMaintenancePolicy afterSuccess = s == Start.CUSTOMER ? imp(90, 150) : imp(100, 200);
            rows.add(row("CONFIG_APPLY / " + s + " / success", () -> configApply(s, false, afterSuccess)));
            InstanceMaintenancePolicy afterFailure = switch (s) {
                case ABSENT -> null;
                case CUSTOMER -> imp(90, 150);
                case OWNED, EDITED, REMOVED -> imp(100, 200);
            };
            rows.add(row("CONFIG_APPLY / " + s + " / post-mutation failure, compensation",
                    () -> configApply(s, true, afterFailure)));
        }
        // PROFILE_CHANGE shares the CONFIG_APPLY program; one row per policy owner that matters.
        rows.add(row("PROFILE_CHANGE / ABSENT / post-mutation failure, compensation", () -> profileChangeFailure()));
        // ADOPT compensation: the plan's "current" omits an originally absent policy; the linked snapshot decides.
        rows.add(row("ADOPT / ABSENT / post-mutation failure, compensation", () -> adoptFailure(null)));
        rows.add(row("ADOPT / CUSTOMER / post-mutation failure, compensation", () -> adoptFailure(imp(90, 150))));
        // REVERT.
        rows.add(row("REVERT / OWNED over absent original / success", () -> revert(imp(100, 200), null, false, null)));
        rows.add(row("REVERT / CUSTOMER / success", () -> revert(imp(90, 150), imp(90, 150), false, imp(90, 150))));
        rows.add(row("REVERT / OWNED over absent original / unwrap refused, single-override fallback",
                () -> revert(imp(100, 200), null, true, null)));
        rows.add(row("REVERT / CUSTOMER / unwrap refused, single-override fallback",
                () -> revert(imp(90, 150), imp(90, 150), true, imp(90, 150))));
        // D-23b-01: re-enable after a revert. A customer policy set after the revert is never overwritten; a group
        // still without one gets XamOps' 100/200 again.
        rows.add(row("ENABLE after REVERT / CUSTOMER 90/120 set after the revert / success, policy kept",
                () -> AwsConfirmedOpsIT.reEnableAfterRevert(imp(90, 120), imp(90, 120))));
        rows.add(row("ENABLE after REVERT / ABSENT / success, XamOps owns 100/200 again",
                () -> AwsConfirmedOpsIT.reEnableAfterRevert(null, imp(100, 200))));
        // FALLBACK enter and exit.
        for (Start s : Start.values()) {
            // Fallback only writes a policy XamOps owns; a group XamOps never gave one stays as it is.
            InstanceMaintenancePolicy afterEnter = s == Start.CUSTOMER ? imp(90, 150)
                    : s == Start.ABSENT ? null : imp(100, 200);
            rows.add(row("FALLBACK_ENTER / " + s + " / success", () -> fallbackEnter(s, afterEnter)));
            rows.add(row("FALLBACK_EXIT / " + s + " / success", () -> fallbackExit(s, afterEnter)));
        }
        // A customer policy with Max only: AWS API reference documents no default Min, so it warns and is never rewritten.
        rows.add(row("CONFIG_APPLY / CUSTOMER Max-only policy / IMP_MIN_HEALTHY_LOW shown, policy untouched, success",
                () -> customerMaxOnlyConfigApply()));
        // FALLBACK_ENTER preempting the first CONFIG_APPLY that gives the group XamOps' policy (review follow-up 1).
        rows.add(row("FALLBACK_ENTER preempting CONFIG_APPLY that adds the policy / success, no drift on next sweep",
                () -> fallbackPreemptsPolicyOwningConfigApply(false)));
        rows.add(row("FALLBACK_ENTER preempting CONFIG_APPLY that adds the policy / policy removed meanwhile, "
                + "fallback re-applies it, no drift on next sweep", () -> fallbackPreemptsPolicyOwningConfigApply(true)));
        return rows.stream().map(r -> Arguments.of(r));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    void everyPolicyPathLeavesTheLivePolicyWhereTheDesignSays(Row row) throws Exception {
        row.body().run();
    }

    // ---- rows ----

    private static void configApply(Start s, boolean fail, InstanceMaintenancePolicy expected) {
        int desiredCapacity = fail ? 5 : 4;
        AutoScalingGroup live = converted(livePolicy(s), 50, desiredCapacity, 4);
        try (AwsOpsHarness h = new AwsOpsHarness(live, GroupState.ACTIVE, "MANAGED", desired(50, ownedInDesired(s)), null,
                null, null)) {
            if (fail) {
                spotShortage(h);
            } else {
                for (int i = 1; i <= 3; i++) {
                    h.f.spotInstances.add("i-" + (5 - i));
                }
            }
            UUID op = h.planAndConfirm(request(OperationKind.CONFIG_APPLY, desired(50, ownedInDesired(s))));
            String end = fail ? runThroughCompensation(h, op) : runToEnd(h, op);
            assertThat(end).as("op outcome (error " + h.jdbc.queryForList(
                    "SELECT error_code FROM spot_operation WHERE id = ?", String.class, op) + ")").isEqualTo("SUCCEEDED");
            assertThat(h.groupState()).isEqualTo("ACTIVE");
            assertPolicy(h, expected);
        }
    }

    private static void profileChangeFailure() {
        AutoScalingGroup live = converted(null, 50, 5, 4);
        try (AwsOpsHarness h = new AwsOpsHarness(live, GroupState.ACTIVE, "MANAGED", desired(50, false), null, null,
                null)) {
            spotShortage(h);
            UUID op = h.planAndConfirm(request(OperationKind.PROFILE_CHANGE, desired(50, false)));
            assertThat(runThroughCompensation(h, op)).isEqualTo("SUCCEEDED");
            assertPolicy(h, null);
        }
    }

    /** An ADOPT that already sent its update (100/200 over an originally absent policy), then fails after it. */
    private static void adoptFailure(InstanceMaintenancePolicy original) {
        InstanceMaintenancePolicy liveAfterAdopt = original == null ? imp(100, 200) : original;
        String target = desired(50, original == null);
        try (AwsOpsHarness h = new AwsOpsHarness(converted(liveAfterAdopt, 50, 5, 4), GroupState.APPLYING, "MANAGED",
                desired(50, false), null, null, null)) {
            h.linkSnapshot(converted(original, 50, 4, 4));
            h.startInFlight(OperationKind.ADOPT, target);
            // A real ADOPT compensates toward the prior configuration (core 2.2.1), not a snapshot revert.
            h.jdbc.update("UPDATE spot_operation SET compensation_target = 'PRIOR_CONFIG' WHERE id = ?", h.enableOpId);
            // The stored PRIOR_CONFIG is the plan's "current", which never carries a policy the customer owns or that was absent.
            h.jdbc.update("UPDATE spot_operation SET compensation_config = ?::jsonb WHERE id = ?", desired(50, false),
                    h.enableOpId);
            spotShortage(h);
            assertThat(runThroughCompensation(h, h.enableOpId)).isEqualTo("SUCCEEDED");
            assertPolicy(h, original);
        }
    }

    private static void revert(InstanceMaintenancePolicy livePolicy, InstanceMaintenancePolicy originalPolicy,
                               boolean refuseUnwrap, InstanceMaintenancePolicy expected) {
        AutoScalingGroup original = originalPolicy == null ? FakeAws.asg("web-asg").build()
                : FakeAws.asg("web-asg").instanceMaintenancePolicy(originalPolicy).build();
        boolean owned = originalPolicy == null;
        try (AwsOpsHarness h = new AwsOpsHarness(converted(livePolicy, 50, 4, 4), original, GroupState.ACTIVE,
                desired(50, owned))) {
            if (refuseUnwrap) {
                doAnswer(inv -> {
                    UpdateAutoScalingGroupRequest r = inv.getArgument(0);
                    if (r.launchTemplate() != null && r.mixedInstancesPolicy() == null) {
                        throw AwsServiceException.builder().statusCode(400).awsErrorDetails(AwsErrorDetails.builder()
                                .errorCode("ValidationError").build()).build();
                    }
                    h.f.log.add("update");
                    h.f.updates.add(r);
                    h.f.applyUpdate(r);
                    return UpdateAutoScalingGroupResponse.builder().build();
                }).when(h.f.write).updateAutoScalingGroup(any(UpdateAutoScalingGroupRequest.class));
            }
            h.runner.runClaimed(h.lease, h.f.opId);
            assertThat(runToEnd(h, h.f.opId)).as("op outcome (error " + h.opError() + ")").isEqualTo("SUCCEEDED");
            assertThat(h.groupState()).isEqualTo("REVERTED");
            assertPolicy(h, expected);
        }
    }

    private static void fallbackEnter(Start s, InstanceMaintenancePolicy expected) {
        AutoScalingGroup live = withInstances(converted(livePolicy(s), 50, 5, 4), 4);
        try (AwsOpsHarness h = new AwsOpsHarness(live, GroupState.ACTIVE, "MANAGED", desired(50, ownedInDesired(s)),
                OperationKind.FALLBACK_ENTER, null, null)) {
            h.runner.runClaimed(h.lease, h.f.opId);
            assertThat(h.opError()).as("no error").isNull();
            assertThat(h.f.updates).as("the drain was sent").isNotEmpty();
            assertThat(h.f.updates.get(0).mixedInstancesPolicy().instancesDistribution()
                    .onDemandPercentageAboveBaseCapacity()).isEqualTo(100);
            assertPolicy(h, expected);
        }
    }

    private static void fallbackExit(Start s, InstanceMaintenancePolicy expected) {
        AutoScalingGroup live = withInstances(converted(livePolicy(s), 100, 4, 4), 4);
        try (AwsOpsHarness h = new AwsOpsHarness(live, GroupState.FALLBACK, "FALLBACK", desired(50, ownedInDesired(s)),
                OperationKind.FALLBACK_EXIT, null, null)) {
            for (int i = 0; i < AwsProfiles.spotCount(4, 1, 50); i++) {
                h.f.spotInstances.add("i-" + (4 - i));
            }
            h.runner.runClaimed(h.lease, h.f.opId);
            assertThat(runToEnd(h, h.f.opId)).as("op outcome (error " + h.opError() + ")").isEqualTo("SUCCEEDED");
            assertThat(h.groupState()).isEqualTo("ACTIVE");
            assertPolicy(h, expected);
        }
    }

    /** The production SpotReconciler over the harness context and the harness's AWS provider. */
    private static com.xammer.cloud.service.autospotting.core.reconcile.SpotReconciler realSweep(AwsOpsHarness h) {
        var c = h.ctx;
        var metrics = c.getBean(com.xammer.cloud.service.autospotting.obs.AutoSpottingMetrics.class);
        var store = new com.xammer.cloud.repository.autospotting.ReconcilerStore(c.getBean(javax.sql.DataSource.class));
        var healthPass = new com.xammer.cloud.service.autospotting.core.reconcile.HealthPass(
                c.getBean(com.xammer.cloud.repository.autospotting.SpotOperationRepository.class),
                c.getBean(com.xammer.cloud.service.autospotting.core.ops.PreemptionService.class), store, metrics,
                c.getBean(com.xammer.cloud.service.autospotting.core.reconcile.FallbackEnqueuer.class));
        var lifecycle = c.getBean(com.xammer.cloud.service.autospotting.core.lifecycle.GroupLifecycleService.class);
        return new com.xammer.cloud.service.autospotting.core.reconcile.SpotReconciler(
                new com.xammer.cloud.service.autospotting.spi.ProviderRegistry(java.util.List.of(h.provider)),
                c.getBean(com.xammer.cloud.repository.autospotting.SpotGroupRepository.class),
                c.getBean(com.xammer.cloud.repository.autospotting.SpotOperationRepository.class),
                c.getBean(com.xammer.cloud.repository.autospotting.SpotPlanRepository.class),
                new com.xammer.cloud.service.autospotting.core.reconcile.SweepOrder(store, 200), store,
                c.getBean(com.xammer.cloud.service.autospotting.core.switches.SwitchService.class),
                new com.xammer.cloud.service.autospotting.core.reconcile.SwitchPauseApplier(
                        c.getBean(com.xammer.cloud.repository.autospotting.AutoSpottingSwitchRepository.class), lifecycle),
                new com.xammer.cloud.service.autospotting.core.reconcile.DriftClassifier(),
                new com.xammer.cloud.service.autospotting.core.reconcile.GoneDetector(), healthPass, lifecycle,
                c.getBean(com.xammer.cloud.service.autospotting.core.lease.LeaseService.class),
                c.getBean(com.xammer.cloud.service.autospotting.core.lease.LeaseRegistry.class), null,
                c.getBean(com.xammer.cloud.service.autospotting.core.lease.LeaseProperties.class), metrics,
                java.time.Duration.ofHours(2));
    }
    /**
     * A confirmed CONFIG_APPLY adds XamOps' 100/200 to a group with none and is preempted after its mutation by a
     * FALLBACK_ENTER. The fallback is hashed and sent with the preempted target's ownership, so the next sweep (which
     * reads ownership from the deferred desired_config) sees no drift.
     */
    private static void fallbackPreemptsPolicyOwningConfigApply(boolean policyRemovedMeanwhile) {
        AutoScalingGroup live = converted(null, 50, 5, 4);
        try (AwsOpsHarness h = new AwsOpsHarness(live, GroupState.ACTIVE, "MANAGED", desired(50, false), null, null,
                null)) {
            UUID configOp = h.planAndConfirm(request(OperationKind.CONFIG_APPLY, desired(50, false)));
            h.store.claimForRun(configOp);
            h.runner.runClaimed(h.lease, configOp);
            assertThat(h.f.group().instanceMaintenancePolicy()).as("the config apply wrote XamOps' policy").isNotNull();
            assertThat(h.groupState()).as("the mutated config op leaves the group APPLYING").isEqualTo("APPLYING");
            if (policyRemovedMeanwhile) {
                h.f.replaceGroup(h.f.group().toBuilder().instanceMaintenancePolicy((InstanceMaintenancePolicy) null).build());
            }
            h.f.replaceGroup(withInstances(h.f.group(), 4));
            UUID fallback = UUID.randomUUID();
            h.store.insertQueued(com.xammer.cloud.service.autospotting.core.ops.OperationRecord
                    .builder(fallback, h.f.groupId, 7L, OperationKind.FALLBACK_ENTER)
                    .priorState(GroupState.APPLYING)
                    .compensationTarget(com.xammer.cloud.service.autospotting.core.lifecycle.CompensationTargets
                            .forKind(OperationKind.FALLBACK_ENTER))
                    .requestedBy("system:fallback").build());
            h.jdbc.update("UPDATE spot_operation SET status = 'CANCELLED', cancel_reason = 'PREEMPTED_BY_FALLBACK',"
                    + " preempted_by_op = ?, finished_at = now() WHERE id = ?", fallback, configOp);
            h.store.claimForRun(fallback);
            h.runner.runClaimed(h.lease, fallback);
            assertThat(h.jdbc.queryForObject("SELECT error_code FROM spot_operation WHERE id = ?", String.class,
                    fallback)).as("fallback error").isNull();
            assertThat(h.groupState()).isEqualTo("FALLBACK");
            assertPolicy(h, imp(100, 200));
            com.xammer.cloud.domain.autospotting.SpotGroup row = h.ctx.getBean(
                    com.xammer.cloud.repository.autospotting.SpotGroupRepository.class).findById(h.f.groupId).orElseThrow();
            assertThat(AwsMaintenancePolicy.owns(row.getDesiredConfig())).as("the deferred target owns the policy").isTrue();
            for (int i = 0; i < 2; i++) {
                var status = h.provider.getStatus(row, h.f.aws.view());
                assertThat(new com.xammer.cloud.service.autospotting.core.reconcile.DriftClassifier()
                        .classify(row, status, false, false, false, false, false, false).code())
                        .as("drift on sweep " + (i + 1))
                        .isEqualTo(com.xammer.cloud.service.autospotting.core.reconcile.DriftClassifier.Code.NONE);
            }
            // The real reconciler sweep (lease, getStatus, drift classification, pause) on the persisted group.
            h.ctx.getBean(com.xammer.cloud.service.autospotting.core.lease.LeaseService.class).releaseGroup(h.lease);
            realSweep(h).sweepProvider(com.xammer.cloud.domain.autospotting.Provider.AWS);
            row = h.ctx.getBean(com.xammer.cloud.repository.autospotting.SpotGroupRepository.class)
                    .findById(h.f.groupId).orElseThrow();
            assertThat(row.getState().name()).as("no drift pause on the next sweep").isEqualTo("FALLBACK");
            assertThat(row.getPauseReason()).isNull();
            assertThat(row.getDriftStatus().name()).isEqualTo("NONE");
        }
    }

    /**
     * Review follow-up 3. The AWS API reference for InstanceMaintenancePolicy says Min and Max must both be given and
     * documents no default for an omitted Min, so a Max-only customer policy is treated as possibly allowing a dip.
     */
    private static void customerMaxOnlyConfigApply() {
        InstanceMaintenancePolicy maxOnly = InstanceMaintenancePolicy.builder().maxHealthyPercentage(150).build();
        try (AwsOpsHarness h = new AwsOpsHarness(converted(maxOnly, 50, 4, 4), GroupState.ACTIVE, "MANAGED",
                desired(50, false), null, null, null)) {
            for (int i = 1; i <= 3; i++) {
                h.f.spotInstances.add("i-" + (5 - i));
            }
            UUID op = h.planAndConfirm(request(OperationKind.CONFIG_APPLY, desired(50, false)));
            assertThat(h.jdbc.queryForObject("SELECT warning_ids::text FROM spot_plan WHERE group_id = ?"
                    + " AND kind = 'CONFIG_APPLY'", String.class, h.f.groupId))
                    .as("the plan shows the capacity-dip warning").contains("w-imp-min-healthy-low")
                    .doesNotContain("w-imp-overshoot");
            assertThat(runToEnd(h, op)).as("op outcome (error " + h.jdbc.queryForList(
                    "SELECT error_code FROM spot_operation WHERE id = ?", String.class, op) + ")").isEqualTo("SUCCEEDED");
            InstanceMaintenancePolicy live = h.f.group().instanceMaintenancePolicy();
            assertThat(live.maxHealthyPercentage()).as("the customer's policy is not rewritten").isEqualTo(150);
            assertThat(live.minHealthyPercentage()).isNull();
        }
    }
}
