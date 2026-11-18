package com.xammer.cloud.service.autospotting.aws;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.OperationKind;
import com.xammer.cloud.domain.autospotting.Provider;
import com.xammer.cloud.repository.autospotting.SpotGroupRepository;
import com.xammer.cloud.service.autospotting.AutoSpottingBlockedException;
import com.xammer.cloud.service.autospotting.AutoSpottingFeatureFlags;
import com.xammer.cloud.service.autospotting.core.AutoSpottingApiException;
import com.xammer.cloud.service.autospotting.core.LegacyDisableGuard;
import com.xammer.cloud.service.autospotting.core.ops.PreemptionService;
import com.xammer.cloud.service.autospotting.core.plan.RevertPreconditions;
import com.xammer.cloud.service.autospotting.core.switches.ActionGate;
import com.xammer.cloud.service.autospotting.core.switches.AutoSpottingSwitchProperties;
import com.xammer.cloud.service.autospotting.core.switches.SwitchState;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;
import software.amazon.awssdk.services.autoscaling.model.InstancesDistribution;
import software.amazon.awssdk.services.autoscaling.model.LaunchTemplate;
import software.amazon.awssdk.services.autoscaling.model.LaunchTemplateOverrides;
import software.amazon.awssdk.services.autoscaling.model.LaunchTemplateSpecification;
import software.amazon.awssdk.services.autoscaling.model.MixedInstancesPolicy;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * REVERT on real PostgreSQL with the real runner, lease, store and lifecycle, and the real AWS provider over a
 * stubbed SDK (plan-phase-19.md task 19.4; AC-REV-01, AC-REV-04, AC-REV-05, PD-07, DR-03, DR-04). No AWS call is
 * ever made; the mutation switches are on in this test context only.
 */
@Tag("containers")
class AwsRevertEndToEndIT {
    private static final String DESIRED = "{\"shape\":\"MIXED_INSTANCES_POLICY\",\"launchTemplate\":{\"id\":\"lt-0abc\","
            + "\"version\":\"$Default\"},\"overrides\":[\"m5.large\",\"m5a.large\",\"m6i.large\"],"
            + "\"instancesDistribution\":{\"onDemandAllocationStrategy\":\"prioritized\",\"onDemandBaseCapacity\":1,"
            + "\"onDemandPercentageAboveBaseCapacity\":50,\"spotAllocationStrategy\":\"price-capacity-optimized\"},"
            + "\"capacityRebalance\":true}";

    private static AutoScalingGroup converted() {
        return FakeAws.asg("web-asg").launchTemplate((LaunchTemplateSpecification) null).capacityRebalance(true)
                .tags(FakeAws.tag(AwsGroupFacts.MARKER_TAG, "marker"))
                .mixedInstancesPolicy(MixedInstancesPolicy.builder().launchTemplate(LaunchTemplate.builder()
                        .launchTemplateSpecification(LaunchTemplateSpecification.builder().launchTemplateId("lt-0abc")
                                .version("$Default").build())
                        .overrides(List.of("m5.large", "m5a.large", "m6i.large").stream()
                                .map(t -> LaunchTemplateOverrides.builder().instanceType(t).build()).toList()).build())
                        .instancesDistribution(InstancesDistribution.builder().onDemandAllocationStrategy("prioritized")
                                .onDemandBaseCapacity(1).onDemandPercentageAboveBaseCapacity(50)
                                .spotAllocationStrategy("price-capacity-optimized").build()).build()).build();
    }

    private static AwsOpsHarness harness(GroupState state) {
        return new AwsOpsHarness(converted(), FakeAws.asg("web-asg").build(), state, DESIRED);
    }

    private static String progress(AwsOpsHarness h) {
        return h.jdbc.queryForObject("SELECT progress::text FROM spot_operation WHERE id = ?", String.class, h.f.opId);
    }

    @Test
    void revertDrainsLaunchFirstWithVisibleProgressThenRestoresAndEndsReverted() {
        try (AwsOpsHarness h = harness(GroupState.ACTIVE)) {
            h.f.spotInstances.add("i-1");
            h.f.spotInstances.add("i-2");
            h.runner.runClaimed(h.lease, h.f.opId);
            // Step 0 passed and the drain went out: the group entered REVERTING with the first mutation.
            assertThat(h.opStatus()).isEqualTo("WAITING");
            assertThat(h.opWaitReason()).isEqualTo("CLOUD_PROGRESS");
            assertThat(h.groupState()).isEqualTo("REVERTING");
            assertThat(h.jdbc.queryForObject("SELECT first_mutation_seq FROM spot_operation WHERE id = ?",
                    Integer.class, h.f.opId)).isEqualTo(1);
            assertThat(progress(h)).contains("Spot instances remaining: 2");
            assertThat(h.f.log).as("the drain only, nothing restored while Spot remains").containsExactly("update");
            assertThat(h.f.updates.get(0).mixedInstancesPolicy().instancesDistribution()
                    .onDemandPercentageAboveBaseCapacity()).isEqualTo(100);
            // Spot is gone: the next poll restores the shape, verifies and removes the marker.
            h.f.spotInstances.clear();
            h.f.clock.advance(Duration.ofMinutes(1));
            h.claimAndRun();
            assertThat(h.opStatus()).isEqualTo("SUCCEEDED");
            assertThat(h.groupState()).isEqualTo("REVERTED");
            assertThat(h.f.log).containsExactly("update", "update", "deleteTags");
            assertThat(h.f.group().mixedInstancesPolicy()).isNull();
            assertThat(h.f.group().launchTemplate().launchTemplateId()).isEqualTo("lt-0abc");
            assertThat(h.f.group().capacityRebalance()).isFalse();
            assertThat(h.f.group().tags()).noneMatch(t -> t.key().equals(AwsGroupFacts.MARKER_TAG));
            assertThat(h.count("SELECT count(*) FROM spot_group_transition WHERE group_id = ? AND operation_id = ?"
                    + " AND to_state = 'REVERTING'", h.f.groupId, h.f.opId)).isEqualTo(1);
            assertThat(h.count("SELECT count(*) FROM spot_group_transition WHERE group_id = ? AND operation_id = ?"
                    + " AND to_state = 'REVERTED'", h.f.groupId, h.f.opId)).isEqualTo(1);
            List<Object> steps = h.jdbc.queryForList("SELECT name FROM spot_operation_step WHERE operation_id = ?"
                    + " ORDER BY seq", Object.class, h.f.opId);
            assertThat(steps).containsExactly("VERIFY_PRECONDITIONS", "DRAIN_SPOT", "WAIT_SPOT_DRAINED", "RESTORE_SHAPE",
                    "VERIFY_REVERTED", "REMOVE_MARKER");
            h.f.aws.assertReadOnly();
        }
    }

    @Test
    void aRevertThatFailsBeforeItsFirstMutationEndsFailedLeavesTheGroupActiveAndCanBePlannedAgain() {
        try (AwsOpsHarness h = harness(GroupState.ACTIVE)) {
            // The customer edited the group after the plan: step 0 says PLAN_STALE.
            h.f.replaceGroup(h.f.group().toBuilder().capacityRebalance(false).build());
            h.runner.runClaimed(h.lease, h.f.opId);
            assertThat(h.opStatus()).isEqualTo("FAILED");
            assertThat(h.opError()).isEqualTo("PLAN_STALE");
            assertThat(h.jdbc.queryForObject("SELECT first_mutation_seq FROM spot_operation WHERE id = ?",
                    Integer.class, h.f.opId)).isNull();
            assertThat(h.groupState()).as("it never entered REVERTING").isEqualTo("ACTIVE");
            assertThat(h.count("SELECT count(*) FROM spot_group_transition WHERE group_id = ? AND to_state = 'REVERTING'",
                    h.f.groupId)).isZero();
            assertThat(h.jdbc.queryForObject("SELECT finished_at IS NOT NULL FROM spot_operation WHERE id = ?",
                    Boolean.class, h.f.opId)).as("billing resumes from finished_at (ledger §3.1)").isTrue();
            assertThat(h.f.writeCalls()).isEmpty();
            // The way back: a fresh plan against the group as it is now has no blocker.
            var again = h.provider.createPlan(new com.xammer.cloud.service.autospotting.spi.PlanRequest(
                    new com.xammer.cloud.service.autospotting.spi.GroupRef(Provider.AWS, 7L,
                            FakeAws.REGION + "/web-asg", null), OperationKind.REVERT, null), h.f.aws.view());
            assertThat(again.eligibility().blockers()).isEmpty();
        }
    }

    @Test
    void aRevertThatFailsAfterItsFirstMutationEndsFailedAndTheGroupIsFailedNotStuckReverting() {
        try (AwsOpsHarness h = harness(GroupState.ACTIVE)) {
            h.f.spotInstances.add("i-1");
            h.runner.runClaimed(h.lease, h.f.opId); // drained, waiting for the Spot instance to go
            assertThat(h.groupState()).isEqualTo("REVERTING");
            h.f.spotInstances.clear();
            org.mockito.Mockito.doThrow(software.amazon.awssdk.awscore.exception.AwsServiceException.builder()
                    .statusCode(403).awsErrorDetails(software.amazon.awssdk.awscore.exception.AwsErrorDetails.builder()
                            .errorCode("AccessDenied").build()).build()).when(h.f.write).updateAutoScalingGroup(
                    org.mockito.ArgumentMatchers.any(
                            software.amazon.awssdk.services.autoscaling.model.UpdateAutoScalingGroupRequest.class));
            h.f.clock.advance(Duration.ofMinutes(1));
            h.claimAndRun();
            assertThat(h.opStatus()).isEqualTo("FAILED");
            assertThat(h.groupState()).isEqualTo("FAILED");
            assertThat(h.jdbc.queryForObject("SELECT first_mutation_seq FROM spot_operation WHERE id = ?",
                    Integer.class, h.f.opId)).isEqualTo(1);
        }
    }

    @Test
    void anExactRevertWithoutPassRoleFailsInStepZeroNeverAfterADrain() {
        // The permission is there when the plan is made and gone when the op runs (the stack was changed).
        java.util.concurrent.atomic.AtomicBoolean denied = new java.util.concurrent.atomic.AtomicBoolean(false);
        try (AwsOpsHarness h = new AwsOpsHarness(converted(), FakeAws.asg("web-asg").build(), GroupState.ACTIVE, DESIRED,
                true, f -> {
            org.mockito.Mockito.doAnswer(inv -> {
                software.amazon.awssdk.services.iam.model.SimulatePrincipalPolicyRequest r = inv.getArgument(0);
                return software.amazon.awssdk.services.iam.model.SimulatePrincipalPolicyResponse.builder()
                        .evaluationResults(r.actionNames().stream().map(a ->
                                software.amazon.awssdk.services.iam.model.EvaluationResult.builder().evalActionName(a)
                                        .evalDecision(denied.get() && a.equals("iam:PassRole") ? "implicitDeny" : "allowed")
                                        .build()).toList()).build();
            }).when(f.aws.iam).simulatePrincipalPolicy(
                    org.mockito.ArgumentMatchers.any(software.amazon.awssdk.services.iam.model.SimulatePrincipalPolicyRequest.class));
            org.mockito.Mockito.doReturn(software.amazon.awssdk.services.ec2.model.DescribeLaunchTemplateVersionsResponse
                    .builder().launchTemplateVersions(software.amazon.awssdk.services.ec2.model.LaunchTemplateVersion
                            .builder().versionNumber(7L).launchTemplateData(software.amazon.awssdk.services.ec2.model
                                    .ResponseLaunchTemplateData.builder().imageId("ami-0aaa").instanceType("m5.large")
                                    .iamInstanceProfile(software.amazon.awssdk.services.ec2.model
                                            .LaunchTemplateIamInstanceProfileSpecification.builder().name("p").build())
                                    .build()).build()).build()).when(f.aws.ec2).describeLaunchTemplateVersions(
                    org.mockito.ArgumentMatchers.any(software.amazon.awssdk.services.ec2.model.DescribeLaunchTemplateVersionsRequest.class));
            org.mockito.Mockito.doReturn(software.amazon.awssdk.services.iam.model.GetInstanceProfileResponse.builder()
                    .instanceProfile(software.amazon.awssdk.services.iam.model.InstanceProfile.builder().roles(
                            software.amazon.awssdk.services.iam.model.Role.builder().arn("arn:aws:iam::1:role/r").build())
                            .build()).build()).when(f.aws.iam).getInstanceProfile(org.mockito.ArgumentMatchers.any(
                    software.amazon.awssdk.services.iam.model.GetInstanceProfileRequest.class));
        })) {
            denied.set(true);
            h.f.spotInstances.add("i-1");
            h.runner.runClaimed(h.lease, h.f.opId);
            assertThat(h.opStatus()).isEqualTo("FAILED");
            assertThat(h.opError()).isEqualTo("REVERT_NEEDS_PASSROLE");
            assertThat(h.groupState()).isEqualTo("ACTIVE");
            assertThat(h.f.writeCalls()).as("no Spot was drained").isEmpty();
        }
    }

    @Test
    void aRevertConfirmedWhileEnableIsWaitingPreemptsItAndCompletes() {
        try (AwsOpsHarness h = new AwsOpsHarness(converted(), FakeAws.asg("web-asg").build(), GroupState.ACTIVE,
                DESIRED, false)) {
            h.startEnableInFlight(DESIRED);
            PreemptionService.Preemption verdict = h.ctx.getBean(PreemptionService.class)
                    .preemptFor(h.f.groupId, OperationKind.REVERT, h.f.opId);
            assertThat(verdict.verdict()).isEqualTo(PreemptionService.Verdict.PREEMPTED);
            // The ENABLE sees the cancel at its next boundary and ends CANCELLED; the revert is the restoration.
            h.store.claimForRun(h.enableOpId);
            h.runner.runClaimed(h.lease, h.enableOpId);
            assertThat(h.jdbc.queryForObject("SELECT status FROM spot_operation WHERE id = ?", String.class, h.enableOpId))
                    .isEqualTo("CANCELLED");
            assertThat(h.groupState()).as("a mutated, cancelled ENABLE leaves the group for the revert").isEqualTo("APPLYING");
            h.claimAndRun();
            assertThat(h.opStatus()).isEqualTo("SUCCEEDED");
            assertThat(h.groupState()).isEqualTo("REVERTED");
            assertThat(h.f.group().mixedInstancesPolicy()).isNull();
        }
    }

    @Test
    void aRevertRunsUnderConversionsAccountGlobalAndCloudSwitches() {
        try (AwsOpsHarness h = harness(GroupState.ACTIVE)) {
            for (String key : List.of("CONVERSIONS:aws", "ACCOUNT:7", "GLOBAL", "CLOUD:aws")) {
                h.engageSwitch(key);
            }
            h.runner.runClaimed(h.lease, h.f.opId);
            assertThat(h.opStatus()).as("a SAFETY op pauses only for the freeze").isEqualTo("SUCCEEDED");
            assertThat(h.groupState()).isEqualTo("REVERTED");
        }
    }

    @Test
    void underTheFreezeARevertIsHeldAndMakesNoWrite() {
        try (AwsOpsHarness h = harness(GroupState.ACTIVE)) {
            h.engageSwitch("FREEZE");
            h.runner.runClaimed(h.lease, h.f.opId);
            assertThat(h.opStatus()).isEqualTo("WAITING");
            assertThat(h.opWaitReason()).isEqualTo("SWITCH");
            assertThat(h.groupState()).isEqualTo("ACTIVE");
            assertThat(h.f.writeCalls()).isEmpty();
        }
    }

    @Test
    void theAdmissionGateAndTheConfirmPreconditionsFollowTheRevertRows() {
        AutoSpottingSwitchProperties props = mock(AutoSpottingSwitchProperties.class);
        when(props.v2MutationsEnabled(true)).thenReturn(true);
        when(props.conversionsAllowlistOnly(true)).thenReturn(true);
        ActionGate gate = new ActionGate(props, new AutoSpottingFeatureFlags(true, false, false, false), true);
        CloudAccount account = FakeAws.account();
        // Every non-freeze switch engaged and the account NOT on the allowlist: a revert is still allowed.
        SwitchState engaged = new SwitchState(Set.of("GLOBAL", "CLOUD:aws", "CONVERSIONS:aws", "ACCOUNT:7"), Set.of(),
                false, false);
        assertThat(gate.check(ActionGate.ActionKind.REVERT, account, Provider.AWS, engaged).isAllowed()).isTrue();
        assertThat(gate.check(ActionGate.ActionKind.ENABLE, account, Provider.AWS, engaged).isAllowed()).isFalse();
        // FREEZE refuses it, and an unreadable switch store holds it.
        SwitchState frozen = new SwitchState(Set.of("FREEZE"), Set.of(), false, false);
        assertThat(gate.check(ActionGate.ActionKind.REVERT, account, Provider.AWS, frozen).code())
                .isEqualTo(ActionGate.Code.AS_FROZEN);
        RevertPreconditions pre = new RevertPreconditions(props);
        assertThatThrownBy(() -> pre.checkSwitches(frozen)).isInstanceOfSatisfying(AutoSpottingApiException.class,
                e -> assertThat(e.getCode()).isEqualTo("AS_FROZEN"));
        assertThatThrownBy(() -> pre.checkSwitches(new SwitchState(Set.of(), Set.of(), true, false)))
                .isInstanceOfSatisfying(AutoSpottingApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("AS_SWITCH_UNREADABLE"));
        pre.checkSwitches(engaged); // does not throw: the switches (a) to (d) never stop a revert
        // Phase A disable: with v2-mutations-enabled true every group goes through the v2 revert.
        LegacyDisableGuard guard = new LegacyDisableGuard(mock(SpotGroupRepository.class), props,
                mock(com.xammer.cloud.service.autospotting.core.lease.LeaseService.class),
                mock(com.xammer.cloud.service.autospotting.core.lease.LeaseRegistry.class));
        assertThatThrownBy(() -> guard.beforeDisable(Provider.AWS, 7L, FakeAws.REGION + "/web-asg", "/plan"))
                .isInstanceOf(AutoSpottingBlockedException.class)
                .satisfies(e -> assertThat(((AutoSpottingBlockedException) e).getCode()).isEqualTo("PLAN_REQUIRED"));
    }
}
