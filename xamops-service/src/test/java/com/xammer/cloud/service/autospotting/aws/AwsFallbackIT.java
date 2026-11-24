package com.xammer.cloud.service.autospotting.aws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.autospotting.CompensationTarget;
import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.OperationKind;
import com.xammer.cloud.domain.autospotting.SpotGroup;
import com.xammer.cloud.repository.autospotting.ReconcilerStore;
import com.xammer.cloud.repository.autospotting.SpotGroupRepository;
import com.xammer.cloud.repository.autospotting.SpotOperationRepository;
import com.xammer.cloud.service.autospotting.core.ops.OperationRecord;
import com.xammer.cloud.service.autospotting.core.ops.PreemptionService;
import com.xammer.cloud.service.autospotting.core.reconcile.DriftClassifier;
import com.xammer.cloud.service.autospotting.core.reconcile.FallbackEnqueuer;
import com.xammer.cloud.service.autospotting.core.reconcile.HealthPass;
import com.xammer.cloud.service.autospotting.obs.AutoSpottingMetrics;
import com.xammer.cloud.service.autospotting.spi.GroupStatus;
import com.xammer.cloud.service.autospotting.spi.ReconcileAction;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.autoscaling.model.Activity;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;
import software.amazon.awssdk.services.autoscaling.model.DescribeScalingActivitiesRequest;
import software.amazon.awssdk.services.autoscaling.model.DescribeScalingActivitiesResponse;
import software.amazon.awssdk.services.autoscaling.model.InstancesDistribution;
import software.amazon.awssdk.services.autoscaling.model.LaunchTemplate;
import software.amazon.awssdk.services.autoscaling.model.LaunchTemplateOverrides;
import software.amazon.awssdk.services.autoscaling.model.LaunchTemplateSpecification;
import software.amazon.awssdk.services.autoscaling.model.MixedInstancesPolicy;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * Fallback to On-Demand and back, on real PostgreSQL with the real runner, lease, store, lifecycle and AWS
 * provider over a stubbed SDK (plan-phase-20.md tasks 20.1 to 20.3; AC-HLT-01, AC-HLT-03, AC-HLT-04, AC-HLT-05,
 * AC-EVT-03, AC-CMP-01 coordination). No AWS call is ever made; the mutation switches are on in this test
 * context only, and stay false in every shipped profile.
 */
@Tag("containers")
class AwsFallbackIT {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DESIRED = "{\"shape\":\"MIXED_INSTANCES_POLICY\",\"launchTemplate\":{\"id\":\"lt-0abc\","
            + "\"version\":\"$Default\"},\"overrides\":[\"m5.large\",\"m5a.large\",\"m6i.large\"],"
            + "\"instancesDistribution\":{\"onDemandAllocationStrategy\":\"prioritized\",\"onDemandBaseCapacity\":1,"
            + "\"onDemandPercentageAboveBaseCapacity\":50,\"spotAllocationStrategy\":\"price-capacity-optimized\"},"
            + "\"capacityRebalance\":true}";
    private static final String DEFERRED = DESIRED.replace("\"onDemandPercentageAboveBaseCapacity\":50",
            "\"onDemandPercentageAboveBaseCapacity\":25");

    /** A converted group (1 On-Demand base, {@code pct} percent above it) of {@code desired}, with 4 instances. */
    private static AutoScalingGroup converted(int desired, int pct) {
        return FakeAws.asg("web-asg").launchTemplate((LaunchTemplateSpecification) null).capacityRebalance(true)
                .desiredCapacity(desired).maxSize(8)
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

    private static AutoScalingGroup withInstances(AutoScalingGroup g, int n) {
        var list = new java.util.ArrayList<software.amazon.awssdk.services.autoscaling.model.Instance>();
        for (int i = 1; i <= n; i++) {
            list.add(FakeAws.instance("i-" + i, false));
        }
        return g.toBuilder().instances(list).build();
    }

    private static AwsOpsHarness enter(GroupState state, String pauseReason, GroupState pausedFrom) {
        return new AwsOpsHarness(converted(5, 50), state, "MANAGED", DESIRED, OperationKind.FALLBACK_ENTER,
                pauseReason, pausedFrom);
    }

    private static void failures(AwsOpsHarness h, String message, int count) {
        doAnswer(inv -> {
            Instant now = h.f.clock.instant();
            Activity[] acts = new Activity[count];
            for (int i = 0; i < count; i++) {
                acts[i] = Activity.builder().activityId("fail-" + i).statusCode("Failed")
                        .description("Launching a new EC2 instance").startTime(now.minusSeconds(60L * (i + 1)))
                        .statusMessage(message).build();
            }
            return DescribeScalingActivitiesResponse.builder().activities(acts).build();
        }).when(h.f.aws.asg).describeScalingActivities(any(DescribeScalingActivitiesRequest.class));
    }

    private static String state(AwsOpsHarness h, String column) {
        return h.jdbc.queryForObject("SELECT " + column + "::text FROM spot_group WHERE id = ?", String.class,
                h.f.groupId);
    }

    private static long audits(AwsOpsHarness h, String eventType) {
        return h.count("SELECT count(*) FROM autospotting_audit WHERE group_id = ? AND event_type = ?", h.f.groupId,
                eventType);
    }

    private static int distributionPct(AwsOpsHarness h) {
        return h.f.group().mixedInstancesPolicy().instancesDistribution().onDemandPercentageAboveBaseCapacity();
    }

    // ---- enter: "Fallback with On-Demand short" (ux §9.4) ----

    @Test
    void enterIsFallbackRightAfterTheVerifiedMutationDegradedTwentyMinutesLaterWithDriftNone() {
        try (AwsOpsHarness h = enter(GroupState.ACTIVE, null, null)) {
            h.f.replaceGroup(withInstances(converted(5, 50), 4)); // On-Demand is short too
            h.runner.runClaimed(h.lease, h.f.opId);

            // FALLBACK right after step 2, config_mode FALLBACK, the fingerprint stored, one mutation sent.
            assertThat(h.groupState()).isEqualTo("FALLBACK");
            assertThat(state(h, "config_mode")).isEqualTo("FALLBACK");
            assertThat(state(h, "fallback_fingerprint")).isEqualTo(
                    AwsFingerprint.of(AwsGroupFacts.from(FakeAws.REGION, h.f.group())));
            assertThat(h.f.updates).hasSize(1);
            var sent = h.f.updates.get(0).mixedInstancesPolicy().instancesDistribution();
            assertThat(sent.onDemandPercentageAboveBaseCapacity()).isEqualTo(100);
            assertThat(sent.onDemandBaseCapacity()).as("the base is not touched").isEqualTo(1);
            assertThat(h.f.updates.get(0).mixedInstancesPolicy().launchTemplate()).as("no overrides sent").isNull();
            assertThat(h.f.updates.get(0).capacityRebalance()).as("CapacityRebalance stays").isNull();
            assertThat(h.f.updates.get(0).desiredCapacity()).isNull();
            assertThat(h.opStatus()).as("the tail wait is still running").isEqualTo("WAITING");
            // effective_at equals the mutation step's intent_at, so billing ends at the mutation.
            Timestamp effective = h.jdbc.queryForObject("SELECT effective_at FROM spot_group_transition"
                    + " WHERE group_id = ? AND to_state = 'FALLBACK'", Timestamp.class, h.f.groupId);
            Timestamp intent = h.jdbc.queryForObject("SELECT intent_at FROM spot_operation_step"
                    + " WHERE operation_id = ? AND name = 'UPDATE_DISTRIBUTION'", Timestamp.class, h.f.opId);
            assertThat(effective).isEqualTo(intent);
            assertThat(audits(h, "FALLBACK_ENTERED")).as("the audit trail records the fallback").isEqualTo(1);

            // Two sweeps after entry classify drift NONE (the fallback fingerprint is the one that counts).
            SpotGroup row = h.ctx.getBean(SpotGroupRepository.class).findById(h.f.groupId).orElseThrow();
            for (int i = 0; i < 2; i++) {
                GroupStatus status = h.provider.getStatus(row, h.f.aws.view());
                assertThat(new DriftClassifier().classify(row, status, false, false, false, false, false, false).code())
                        .isEqualTo(DriftClassifier.Code.NONE);
            }

            // 19 minutes in: still FALLBACK. At 20: still short, so DEGRADED, config_mode stays FALLBACK.
            h.f.clock.advance(Duration.ofMinutes(19));
            h.claimAndRun();
            assertThat(h.groupState()).isEqualTo("FALLBACK");
            h.f.clock.advance(Duration.ofMinutes(1));
            h.claimAndRun();
            assertThat(h.groupState()).isEqualTo("DEGRADED");
            assertThat(state(h, "config_mode")).isEqualTo("FALLBACK");

            // Capacity returns: DEGRADED goes back to FALLBACK, never straight to ACTIVE, and the op ends.
            h.f.replaceGroup(withInstances(h.f.group(), 5));
            h.claimAndRun();
            assertThat(h.groupState()).isEqualTo("FALLBACK");
            assertThat(h.opStatus()).isEqualTo("SUCCEEDED");
            assertThat(h.f.updates).as("one mutation in the whole op").hasSize(1);
        }
    }

    @Test
    void theTailWaitEndsCapacityPendingAtTheBoundAndTheGroupStaysInFallback() {
        try (AwsOpsHarness h = enter(GroupState.ACTIVE, null, null)) {
            h.f.replaceGroup(withInstances(converted(5, 50), 4));
            h.runner.runClaimed(h.lease, h.f.opId);
            h.f.clock.advance(Duration.ofMinutes(31));
            h.claimAndRun();
            assertThat(h.opStatus()).isEqualTo("SUCCEEDED");
            assertThat(h.groupState()).as("the finished op does not move the group again").isEqualTo("DEGRADED");
            assertThat(state(h, "config_mode")).isEqualTo("FALLBACK");
        }
    }

    @Test
    void aRevertDuringTheTailWaitPreemptsItAndStartsWithinOnePoll() {
        try (AwsOpsHarness h = enter(GroupState.ACTIVE, null, null)) {
            h.f.replaceGroup(withInstances(converted(5, 50), 4));
            h.runner.runClaimed(h.lease, h.f.opId);
            assertThat(h.groupState()).isEqualTo("FALLBACK");
            UUID revertId = UUID.randomUUID();
            PreemptionService.Preemption p = h.ctx.getBean(PreemptionService.class)
                    .preemptFor(h.f.groupId, OperationKind.REVERT, revertId);
            assertThat(p.verdict()).isEqualTo(PreemptionService.Verdict.PREEMPTED);
            h.store.insertQueued(OperationRecord.builder(revertId, h.f.groupId, 7L, OperationKind.REVERT)
                    .priorState(GroupState.FALLBACK).compensationTarget(CompensationTarget.NONE)
                    .requestedBy("tester").build());
            h.claimAndRun(); // the next poll of the fallback op: it sees the cancel and ends
            assertThat(h.opStatus()).isEqualTo("CANCELLED");
            assertThat(h.store.claimForRun(revertId)).as("the revert starts on the next dispatcher poll").isTrue();
        }
    }

    // ---- enter: switches and pauses (AC-HLT-05) ----

    @Test
    void fallbackRunsUnderConversionsAccountGlobalAndCloudSwitches() {
        try (AwsOpsHarness h = enter(GroupState.ACTIVE, null, null)) {
            h.f.replaceGroup(withInstances(converted(5, 50), 4));
            for (String key : List.of("CONVERSIONS:aws", "ACCOUNT:7", "GLOBAL", "CLOUD:aws")) {
                h.engageSwitch(key);
            }
            h.runner.runClaimed(h.lease, h.f.opId);
            assertThat(h.groupState()).as("a SAFETY op pauses only for the freeze").isEqualTo("FALLBACK");
            assertThat(h.f.updates).hasSize(1);
        }
    }

    @Test
    void underTheFreezeFallbackIsHeldWithNoWriteAndNothingIsEnqueued() {
        try (AwsOpsHarness h = enter(GroupState.ACTIVE, null, null)) {
            h.engageSwitch("FREEZE");
            h.runner.runClaimed(h.lease, h.f.opId);
            assertThat(h.opStatus()).isEqualTo("WAITING");
            assertThat(h.opWaitReason()).isEqualTo("SWITCH");
            assertThat(h.groupState()).isEqualTo("ACTIVE");
            assertThat(h.f.writeCalls()).isEmpty();
        }
        try (AwsOpsHarness h = new AwsOpsHarness(converted(5, 50), GroupState.ACTIVE, "MANAGED", DESIRED, null, null,
                null)) {
            h.engageSwitch("FREEZE");
            SpotGroup row = h.ctx.getBean(SpotGroupRepository.class).findById(h.f.groupId).orElseThrow();
            assertThat(h.ctx.getBean(FallbackEnqueuer.class).enqueueEnter(row, "TEST"))
                    .isEqualTo(FallbackEnqueuer.Result.FROZEN);
            assertThat(h.count("SELECT count(*) FROM spot_operation WHERE group_id = ?", h.f.groupId)).isZero();
        }
    }

    @Test
    void aGroupPausedByTheAccountSwitchFallsBackAndStaysPausedFromFallback() {
        try (AwsOpsHarness h = enter(GroupState.PAUSED, "ACCOUNT_SWITCH", GroupState.ACTIVE)) {
            h.f.replaceGroup(withInstances(converted(5, 50), 4));
            h.engageSwitch("ACCOUNT:7");
            h.runner.runClaimed(h.lease, h.f.opId);
            assertThat(h.groupState()).isEqualTo("PAUSED");
            assertThat(state(h, "paused_from_state")).isEqualTo("FALLBACK");
            assertThat(state(h, "pause_reason")).isEqualTo("ACCOUNT_SWITCH");
            assertThat(state(h, "config_mode")).isEqualTo("FALLBACK");
            assertThat(h.f.updates).hasSize(1);
        }
    }

    // ---- enter: "Fallback defers a config change" (ux §9.4) ----

    @Test
    void fallbackDefersAPreemptedConfigChangeToTheNextExit() {
        try (AwsOpsHarness h = enter(GroupState.APPLYING, null, null)) {
            h.f.replaceGroup(withInstances(converted(5, 25), 4));
            UUID configOp = UUID.randomUUID();
            h.store.insertQueued(OperationRecord.builder(configOp, h.f.groupId, 7L, OperationKind.CONFIG_APPLY)
                    .priorState(GroupState.ACTIVE).compensationTarget(CompensationTarget.PRIOR_CONFIG)
                    .targetConfigJson(DEFERRED).requestedBy("tester").build());
            h.jdbc.update("UPDATE spot_operation SET status = 'CANCELLED', cancel_reason = 'PREEMPTED_BY_FALLBACK',"
                    + " preempted_by_op = ?, first_mutation_seq = 3, finished_at = now() WHERE id = ?", h.f.opId, configOp);
            h.runner.runClaimed(h.lease, h.f.opId);
            assertThat(h.groupState()).isEqualTo("FALLBACK");
            assertThat(h.f.updates).as("a group half-way through a change still falls back").hasSize(1);
            assertThat(distributionPct(h)).isEqualTo(100);
            assertThat(state(h, "config_mode")).isEqualTo("FALLBACK");
            assertThat(h.jdbc.queryForObject("SELECT desired_config::text FROM spot_group WHERE id = ?", String.class,
                    h.f.groupId)).satisfies(json -> {
                try {
                    assertThat(JSON.readTree(json)).isEqualTo(JSON.readTree(DEFERRED));
                } catch (java.io.IOException e) {
                    throw new AssertionError(e);
                }
            });
            assertThat(audits(h, "CONFIG_DEFERRED_BY_FALLBACK")).isEqualTo(1);

            // The next FALLBACK_EXIT targets it, once the enter has finished its tail wait.
            SpotGroup busy = h.ctx.getBean(SpotGroupRepository.class).findById(h.f.groupId).orElseThrow();
            assertThat(h.ctx.getBean(FallbackEnqueuer.class).enqueueExit(busy, Instant.now().plus(Duration.ofHours(3))))
                    .as("not while the enter is still running").isEqualTo(FallbackEnqueuer.Result.BUSY);
            h.f.replaceGroup(withInstances(h.f.group(), 5));
            h.claimAndRun();
            assertThat(h.opStatus()).isEqualTo("SUCCEEDED");
            SpotGroup row = h.ctx.getBean(SpotGroupRepository.class).findById(h.f.groupId).orElseThrow();
            assertThat(h.ctx.getBean(FallbackEnqueuer.class).enqueueExit(row, Instant.now().plus(Duration.ofHours(3))))
                    .isEqualTo(FallbackEnqueuer.Result.ENQUEUED);
            assertThat(h.jdbc.queryForObject("SELECT target_config::text FROM spot_operation WHERE group_id = ?"
                    + " AND kind = 'FALLBACK_EXIT'", String.class, h.f.groupId)).satisfies(json -> {
                try {
                    assertThat(JSON.readTree(json)).isEqualTo(JSON.readTree(DEFERRED));
                } catch (java.io.IOException e) {
                    throw new AssertionError(e);
                }
            });
        }
    }

    // ---- the reconciler's decision to the running op ----

    private HealthPass healthPass(AwsOpsHarness h) {
        return new HealthPass(h.ctx.getBean(SpotOperationRepository.class), h.ctx.getBean(PreemptionService.class),
                new ReconcilerStore(h.ctx.getBean(DataSource.class)), h.ctx.getBean(AutoSpottingMetrics.class),
                h.ctx.getBean(FallbackEnqueuer.class));
    }

    private List<ReconcileAction> sweep(AwsOpsHarness h) {
        SpotGroup group = h.ctx.getBean(SpotGroupRepository.class).findById(h.f.groupId).orElseThrow();
        GroupStatus status = h.provider.getStatus(group, h.f.aws.view());
        List<ReconcileAction> actions = h.provider.reconcile(group, status);
        healthPass(h).run(group, status, actions);
        return actions;
    }

    @Test
    void threeSpotFailuresWithAShortfallEnqueueAFallbackThatRunsToFallback() {
        try (AwsOpsHarness h = new AwsOpsHarness(withInstances(converted(5, 50), 4), GroupState.ACTIVE, "MANAGED",
                DESIRED, null, null, null)) {
            failures(h, "There is no Spot capacity available that matches your request.", 3);
            assertThat(sweep(h)).contains(ReconcileAction.ENQUEUE_FALLBACK);
            UUID opId = h.jdbc.queryForObject("SELECT id FROM spot_operation WHERE group_id = ?"
                    + " AND kind = 'FALLBACK_ENTER'", UUID.class, h.f.groupId);
            assertThat(h.count("SELECT count(*) FROM spot_operation WHERE group_id = ? AND plan_id IS NULL"
                    + " AND action_class = 'SAFETY'", h.f.groupId)).isEqualTo(1);
            assertThat(audits(h, "FALLBACK_ENTER_QUEUED")).isEqualTo(1);
            // A second sweep while it is queued does not enqueue another.
            sweep(h);
            assertThat(h.count("SELECT count(*) FROM spot_operation WHERE group_id = ? AND kind = 'FALLBACK_ENTER'",
                    h.f.groupId)).isEqualTo(1);
            assertThat(h.store.claimForRun(opId)).isTrue();
            h.runner.runClaimed(h.lease, opId);
            assertThat(h.groupState()).isEqualTo("FALLBACK");
            assertThat(audits(h, "FALLBACK_ENTERED")).isEqualTo(1);
        }
    }

    @Test
    void onDemandLaunchFailuresNeverEnqueueAFallbackTheyDegradeTheGroup() {
        try (AwsOpsHarness h = new AwsOpsHarness(withInstances(converted(5, 50), 4), GroupState.ACTIVE, "MANAGED",
                DESIRED, null, null, null)) {
            failures(h, "Could not launch On-Demand Instances. InsufficientInstanceCapacity - We currently do not "
                    + "have sufficient m5.large capacity in the Availability Zone you requested.", 4);
            List<ReconcileAction> actions = sweep(h);
            assertThat(actions).doesNotContain(ReconcileAction.ENQUEUE_FALLBACK);
            assertThat(actions).contains(ReconcileAction.MARK_DEGRADED);
            assertThat(h.count("SELECT count(*) FROM spot_operation WHERE group_id = ?", h.f.groupId)).isZero();
        }
    }

    @Test
    void aShortfallIsOnlyActedOnAfterTenMinutesAcrossTwoSweeps() {
        try (AwsOpsHarness h = new AwsOpsHarness(withInstances(converted(5, 50), 4), GroupState.ACTIVE, "MANAGED",
                DESIRED, null, null, null)) {
            failures(h, "There is no Spot capacity available that matches your request.", 1);
            assertThat(sweep(h)).doesNotContain(ReconcileAction.ENQUEUE_FALLBACK); // first observation
            h.f.clock.advance(Duration.ofMinutes(9));
            failures(h, "There is no Spot capacity available that matches your request.", 1);
            assertThat(sweep(h)).doesNotContain(ReconcileAction.ENQUEUE_FALLBACK);
            h.f.clock.advance(Duration.ofMinutes(1));
            failures(h, "There is no Spot capacity available that matches your request.", 1);
            assertThat(sweep(h)).contains(ReconcileAction.ENQUEUE_FALLBACK);
        }
    }

    // ---- exit, backoff and the daily cap (design-aws.md §6.5) ----

    private static AwsOpsHarness exit(AutoScalingGroup live) {
        return new AwsOpsHarness(live, GroupState.FALLBACK, "FALLBACK", DESIRED, OperationKind.FALLBACK_EXIT, null,
                null);
    }

    @Test
    void exitReturnsToTheDesiredConfigurationAndClearsTheFallbackMode() {
        try (AwsOpsHarness h = exit(withInstances(converted(4, 100), 4))) {
            int spot = AwsProfiles.spotCount(4, 1, 50);
            for (int i = 0; i < spot; i++) {
                h.f.spotInstances.add("i-" + (4 - i));
            }
            h.runner.runClaimed(h.lease, h.f.opId);
            assertThat(h.groupState()).as("surge-first like a conversion: APPLYING while it runs").isIn("APPLYING",
                    "ACTIVE");
            while (!"SUCCEEDED".equals(h.opStatus()) && !"FAILED".equals(h.opStatus())) {
                h.f.clock.advance(Duration.ofMinutes(1));
                h.claimAndRun();
            }
            assertThat(h.opStatus()).isEqualTo("SUCCEEDED");
            assertThat(h.groupState()).isEqualTo("ACTIVE");
            assertThat(state(h, "config_mode")).isEqualTo("MANAGED");
            assertThat(state(h, "fallback_fingerprint")).isNull();
            assertThat(distributionPct(h)).isEqualTo(50);
            assertThat(audits(h, "FALLBACK_EXITED")).isEqualTo(1);
        }
    }

    @Test
    void aFailedExitCompensatesBackToFallbackAndTheNextAttemptWaitsLonger() {
        try (AwsOpsHarness h = exit(withInstances(converted(5, 100), 4))) {
            failures(h, "There is no Spot capacity available that matches your request.", 2);
            h.runner.runClaimed(h.lease, h.f.opId);
            assertThat(h.groupState()).isEqualTo("APPLYING");
            h.f.clock.advance(Duration.ofMinutes(11));
            h.claimAndRun();
            assertThat(h.opPhase()).as("the shortage compensates toward FALLBACK").isEqualTo("COMPENSATING");
            assertThat(distributionPct(h)).as("100% On-Demand again").isEqualTo(100);
            h.f.replaceGroup(withInstances(h.f.group(), 5));
            h.claimAndRun();
            assertThat(h.opStatus()).isEqualTo("SUCCEEDED");
            assertThat(h.groupState()).isEqualTo("FALLBACK");
            assertThat(state(h, "config_mode")).isEqualTo("FALLBACK");
            assertThat(state(h, "fallback_fingerprint")).isEqualTo("fp-old");
            Instant finished = h.jdbc.queryForObject("SELECT finished_at FROM spot_operation WHERE id = ?",
                    Timestamp.class, h.f.opId).toInstant();
            SpotGroup row = h.ctx.getBean(SpotGroupRepository.class).findById(h.f.groupId).orElseThrow();
            FallbackEnqueuer enqueuer = h.ctx.getBean(FallbackEnqueuer.class);
            assertThat(enqueuer.enqueueExit(row, finished.plus(Duration.ofMinutes(59))))
                    .isEqualTo(FallbackEnqueuer.Result.TOO_EARLY);
            assertThat(enqueuer.enqueueExit(row, finished.plus(Duration.ofMinutes(61))))
                    .isEqualTo(FallbackEnqueuer.Result.ENQUEUED);
        }
    }

    @Test
    void anExitWaitsForTheDwellAndForADesiredConfigToReturnTo() {
        try (AwsOpsHarness h = new AwsOpsHarness(withInstances(converted(4, 100), 4), GroupState.FALLBACK, "FALLBACK",
                DESIRED, null, null, null)) {
            Instant since = Instant.now().minus(Duration.ofMinutes(30));
            h.jdbc.update("UPDATE spot_group SET state_changed_at = ? WHERE id = ?", Timestamp.from(since), h.f.groupId);
            SpotGroup row = h.ctx.getBean(SpotGroupRepository.class).findById(h.f.groupId).orElseThrow();
            FallbackEnqueuer enqueuer = h.ctx.getBean(FallbackEnqueuer.class);
            assertThat(enqueuer.enqueueExit(row, since.plus(Duration.ofMinutes(59))))
                    .isEqualTo(FallbackEnqueuer.Result.TOO_EARLY);
            h.jdbc.update("UPDATE spot_group SET desired_config = NULL WHERE id = ?", h.f.groupId);
            row = h.ctx.getBean(SpotGroupRepository.class).findById(h.f.groupId).orElseThrow();
            assertThat(enqueuer.enqueueExit(row, since.plus(Duration.ofMinutes(61))))
                    .isEqualTo(FallbackEnqueuer.Result.NO_TARGET);
        }
    }

    private static void seedHistory(AwsOpsHarness h, Instant at, int enters, int exits) {
        for (int i = 0; i < enters + exits; i++) {
            UUID id = UUID.randomUUID();
            OperationKind kind = i < enters ? OperationKind.FALLBACK_ENTER : OperationKind.FALLBACK_EXIT;
            h.store.insertQueued(OperationRecord.builder(id, h.f.groupId, 7L, kind)
                    .priorState(GroupState.ACTIVE).compensationTarget(CompensationTargets(kind))
                    .requestedBy("system:fallback").build());
            h.jdbc.update("UPDATE spot_operation SET status = 'SUCCEEDED', created_at = ?, finished_at = ?"
                    + " WHERE id = ?", Timestamp.from(at.minusSeconds(60L * (i + 1))), Timestamp.from(at.minusSeconds(30L)), id);
        }
    }

    private static CompensationTarget CompensationTargets(OperationKind kind) {
        return com.xammer.cloud.service.autospotting.core.lifecycle.CompensationTargets.forKind(kind);
    }

    @Test
    void theDailyCapHoldsAnExitButNeverAnEnterAndNeverDegradesTheGroup() {
        try (AwsOpsHarness h = new AwsOpsHarness(withInstances(converted(4, 100), 4), GroupState.FALLBACK, "FALLBACK",
                DESIRED, null, null, null)) {
            Instant now = Instant.parse("2026-11-20T15:00:00Z");
            h.jdbc.update("UPDATE spot_group SET state_changed_at = ? WHERE id = ?",
                    Timestamp.from(now.minus(Duration.ofHours(5))), h.f.groupId);
            seedHistory(h, now, 3, 1); // 4 transitions today: an exit would leave no room for an enter
            SpotGroup row = h.ctx.getBean(SpotGroupRepository.class).findById(h.f.groupId).orElseThrow();
            FallbackEnqueuer enqueuer = h.ctx.getBean(FallbackEnqueuer.class);
            assertThat(enqueuer.enqueueExit(row, now)).isEqualTo(FallbackEnqueuer.Result.CAPPED);
            assertThat(h.groupState()).as("the cap never makes the group DEGRADED").isEqualTo("FALLBACK");
            // After 00:00 UTC the day is new and the exit goes ahead.
            assertThat(enqueuer.enqueueExit(row, Instant.parse("2026-11-21T00:01:00Z")))
                    .isEqualTo(FallbackEnqueuer.Result.ENQUEUED);
        }
    }

    @Test
    void anEnterIsAlwaysAllowedEvenWhenTheDayIsAtTheCap() {
        try (AwsOpsHarness h = new AwsOpsHarness(withInstances(converted(5, 50), 4), GroupState.ACTIVE, "MANAGED",
                DESIRED, null, null, null)) {
            seedHistory(h, Instant.now(), 3, 3);
            SpotGroup row = h.ctx.getBean(SpotGroupRepository.class).findById(h.f.groupId).orElseThrow();
            assertThat(h.ctx.getBean(FallbackEnqueuer.class).enqueueEnter(row, "TEST"))
                    .isEqualTo(FallbackEnqueuer.Result.ENQUEUED);
        }
    }

    @Test
    void aGroupAlreadyAtHundredPercentOnDemandHasNoPathAndTheEnterEndsBeforeAnyWrite() {
        try (AwsOpsHarness h = new AwsOpsHarness(withInstances(converted(5, 100), 4), GroupState.ACTIVE, "MANAGED",
                DESIRED, OperationKind.FALLBACK_ENTER, null, null)) {
            h.runner.runClaimed(h.lease, h.f.opId);
            assertThat(h.opStatus()).isEqualTo("FAILED");
            assertThat(h.opError()).isEqualTo("NO_FALLBACK_PATH");
            assertThat(h.groupState()).isEqualTo("ACTIVE");
            assertThat(h.f.writeCalls()).isEmpty();
        }
    }

    @Test
    void anEnterThatFindsTheShortfallClearedEndsBeforeAnyWrite() {
        try (AwsOpsHarness h = enter(GroupState.ACTIVE, null, null)) {
            h.f.replaceGroup(withInstances(converted(5, 50), 5));
            h.runner.runClaimed(h.lease, h.f.opId);
            assertThat(h.opStatus()).isEqualTo("FAILED");
            assertThat(h.opError()).isEqualTo("FALLBACK_NOT_NEEDED");
            assertThat(h.groupState()).isEqualTo("ACTIVE");
            assertThat(h.f.writeCalls()).isEmpty();
        }
    }
}
