package com.xammer.cloud.service.autospotting.aws;
import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.OperationKind;
import com.xammer.cloud.domain.autospotting.Provider;
import com.xammer.cloud.service.autospotting.spi.GroupRef;
import com.xammer.cloud.service.autospotting.spi.PlanOptions;
import com.xammer.cloud.service.autospotting.spi.PlanRequest;
import com.xammer.cloud.service.autospotting.spi.Profile;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;
import software.amazon.awssdk.services.autoscaling.model.InstanceMaintenancePolicy;
import software.amazon.awssdk.services.autoscaling.model.InstancesDistribution;
import software.amazon.awssdk.services.autoscaling.model.LaunchTemplate;
import software.amazon.awssdk.services.autoscaling.model.LaunchTemplateOverrides;
import software.amazon.awssdk.services.autoscaling.model.LaunchTemplateSpecification;
import software.amazon.awssdk.services.autoscaling.model.MixedInstancesPolicy;
import software.amazon.awssdk.services.autoscaling.model.UpdateAutoScalingGroupRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
/**
 * Review findings of phase 23 (d2309-f2304), proved through the whole path: a plan from the real provider, the real
 * ConfirmService, the real OperationRunner and the AWS fake. The assertions are on the UpdateAutoScalingGroup request
 * the fake received and on the group after verification, never on the plan JSON alone.
 */
@Tag("containers")
class AwsConfirmedOpsIT {
    private static final LaunchTemplateOverrides T3_MICRO = override("t3.micro", null);
    private static final LaunchTemplateOverrides T3A_MICRO = override("t3a.micro", "1");
    /** A customer's own override with a detail (weighted capacity) that must survive the update. */
    private static final LaunchTemplateOverrides CUSTOMER = override("m5.large", "2");
    private static LaunchTemplateOverrides override(String type, String weight) {
        LaunchTemplateOverrides.Builder b = LaunchTemplateOverrides.builder().instanceType(type);
        if (weight != null) {
            b.weightedCapacity(weight);
        }
        return b.build();
    }
    private static AutoScalingGroup converted(List<LaunchTemplateOverrides> overrides, InstanceMaintenancePolicy policy) {
        List<software.amazon.awssdk.services.autoscaling.model.Instance> list = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            list.add(FakeAws.instance("i-" + i, false));
        }
        AutoScalingGroup.Builder b = FakeAws.asg("web-asg").launchTemplate((LaunchTemplateSpecification) null)
                .capacityRebalance(true).instances(list)
                .tags(FakeAws.tag(AwsGroupFacts.MARKER_TAG, "marker"))
                .mixedInstancesPolicy(MixedInstancesPolicy.builder().launchTemplate(LaunchTemplate.builder()
                        .launchTemplateSpecification(LaunchTemplateSpecification.builder().launchTemplateId("lt-0abc")
                                .version("$Default").build())
                        .overrides(overrides).build())
                        .instancesDistribution(InstancesDistribution.builder().onDemandAllocationStrategy("prioritized")
                                .onDemandBaseCapacity(1).onDemandPercentageAboveBaseCapacity(50)
                                .spotAllocationStrategy("price-capacity-optimized").build()).build());
        if (policy != null) {
            b.instanceMaintenancePolicy(policy);
        }
        return b.build();
    }
    private static String desiredWith(List<String> overrides, String extra) {
        return "{\"shape\":\"MIXED_INSTANCES_POLICY\",\"launchTemplate\":{\"id\":\"lt-0abc\",\"version\":\"$Default\"},"
                + "\"overrides\":" + overrides.stream().map(t -> "\"" + t + "\"").toList()
                + ",\"instancesDistribution\":{\"onDemandAllocationStrategy\":\"prioritized\",\"onDemandBaseCapacity\":1,"
                + "\"onDemandPercentageAboveBaseCapacity\":50,\"spotAllocationStrategy\":\"price-capacity-optimized\"},"
                + "\"capacityRebalance\":true" + extra + "}";
    }
    private static PlanRequest request(OperationKind kind, Boolean allowBurstable, String desired) {
        return new PlanRequest(new GroupRef(Provider.AWS, 7L, FakeAws.REGION + "/web-asg", null), kind,
                new Profile("aggressive", null), new PlanOptions(allowBurstable, desired, true));
    }
    private static List<LaunchTemplateOverrides> sentOverrides(UpdateAutoScalingGroupRequest r) {
        return r.mixedInstancesPolicy().launchTemplate() == null ? null
                : r.mixedInstancesPolicy().launchTemplate().overrides();
    }
    private static void burstableOff(List<LaunchTemplateOverrides> live, List<String> liveTypes,
                                     List<LaunchTemplateOverrides> expected) {
        burstableOff(live, liveTypes, expected, "[\"t3a.micro\"]");
    }
    private static void burstableOff(List<LaunchTemplateOverrides> live, List<String> liveTypes,
                                     List<LaunchTemplateOverrides> expected, String remembered) {
        String desired = desiredWith(liveTypes, ",\"options\":{\"allowBurstable\":true,\"burstableAdded\":" + remembered + "}");
        try (AwsOpsHarness h = new AwsOpsHarness(converted(live, null), GroupState.ACTIVE, "MANAGED", desired, null,
                null, null)) {
            h.f.aws.burstableWorld();
            for (int i = 1; i <= 3; i++) {
                h.f.spotInstances.add("i-" + (5 - i));
            }
            UUID op = h.planAndConfirm(request(OperationKind.CONFIG_APPLY, false, desired));
            assertThat(h.runToEnd(op)).as("op error " + h.jdbc.queryForList(
                    "SELECT error_code FROM spot_operation WHERE id = ?", String.class, op)).isEqualTo("SUCCEEDED");
            assertThat(h.groupState()).isEqualTo("ACTIVE");
            List<UpdateAutoScalingGroupRequest> withTypes = h.f.updates.stream().filter(r -> sentOverrides(r) != null).toList();
            assertThat(withTypes).as("one update carried the override list").hasSize(1);
            assertThat(sentOverrides(withTypes.get(0))).as("retained live overrides, details intact").isEqualTo(expected);
            assertThat(h.f.group().mixedInstancesPolicy().launchTemplate().overrides())
                    .as("the group after verification").isEqualTo(expected);
            assertThat(h.jdbc.queryForObject("SELECT desired_config->'options'->>'allowBurstable' FROM spot_group"
                    + " WHERE id = ?", String.class, h.f.groupId)).isEqualTo("false");
        }
    }
    @Test
    void turningBurstableOffRemovesOnlyTheTypesXamOpsAddedAndKeepsTheCustomersOverrideWithItsDetails() {
        burstableOff(List.of(T3_MICRO, CUSTOMER, T3A_MICRO), List.of("t3.micro", "m5.large", "t3a.micro"),
                List.of(T3_MICRO, CUSTOMER));
    }
    /** Review fix: one remembered type already removed by the customer must not make the removal unconfirmable. */
    @Test
    void turningBurstableOffWhenOneRememberedTypeIsAlreadyGoneStillRemovesTheOtherOne() {
        burstableOff(List.of(T3_MICRO, T3A_MICRO), List.of("t3.micro", "t3a.micro"), List.of(T3_MICRO),
                "[\"t3a.micro\",\"t3a.small\"]");
    }
    /** Review follow-up 2: every remembered added type is already gone; the option-off plan must still confirm. */
    @Test
    void turningBurstableOffWhenEveryRememberedTypeIsAlreadyGoneIsNotBlockedAndSucceeds() {
        String desired = desiredWith(List.of("t3.micro"),
                ",\"options\":{\"allowBurstable\":true,\"burstableAdded\":[\"t3a.micro\",\"t3a.small\"]}");
        try (AwsOpsHarness h = new AwsOpsHarness(converted(List.of(T3_MICRO), null), GroupState.ACTIVE, "MANAGED",
                desired, null, null, null)) {
            h.f.aws.burstableWorld();
            for (int i = 1; i <= 3; i++) {
                h.f.spotInstances.add("i-" + (5 - i));
            }
            UUID op = h.planAndConfirm(request(OperationKind.CONFIG_APPLY, false, desired));
            assertThat(h.runToEnd(op)).as("op error " + h.jdbc.queryForList(
                    "SELECT error_code FROM spot_operation WHERE id = ?", String.class, op)).isEqualTo("SUCCEEDED");
            assertThat(h.groupState()).isEqualTo("ACTIVE");
            assertThat(h.f.updates.stream().filter(r -> sentOverrides(r) != null))
                    .as("nothing to remove, so no override list is sent").isEmpty();
            assertThat(h.f.group().mixedInstancesPolicy().launchTemplate().overrides()).isEqualTo(List.of(T3_MICRO));
            assertThat(h.jdbc.queryForObject("SELECT desired_config->'options'->>'allowBurstable' FROM spot_group"
                    + " WHERE id = ?", String.class, h.f.groupId)).isEqualTo("false");
        }
    }
    @Test
    void turningBurstableOffOnASmallBurstableGroupWithNoOtherAlternativeIsNotBlockedAndSucceeds() {
        burstableOff(List.of(T3_MICRO, T3A_MICRO), List.of("t3.micro", "t3a.micro"), List.of(T3_MICRO));
    }
    private static final String ADOPT_TARGET = desiredWith(List.of("m5.large", "m5a.large", "m6i.large"),
            ",\"instanceMaintenancePolicy\":{\"owned\":true,\"min\":100,\"max\":200}");
    private static final String DESIRED_NO_POLICY = desiredWith(List.of("m5.large", "m5a.large", "m6i.large"), "");
    private static AutoScalingGroup three(InstanceMaintenancePolicy policy) {
        return converted(List.of(override("m5.large", null), override("m5a.large", null), override("m6i.large", null)),
                policy);
    }
    private static InstanceMaintenancePolicy imp(int min, int max) {
        return InstanceMaintenancePolicy.builder().minHealthyPercentage(min).maxHealthyPercentage(max).build();
    }
    /** An ADOPT that already sent 100/200, then a REVERT confirmed over it (it preempts the ADOPT). */
    private static AwsOpsHarness adoptThenRevert(InstanceMaintenancePolicy livePolicy, UUID[] revertOp) {
        AwsOpsHarness h = new AwsOpsHarness(three(livePolicy), GroupState.APPLYING, "MANAGED", DESIRED_NO_POLICY, null,
                null, null);
        h.linkSnapshot(FakeAws.asg("web-asg").build());
        h.startInFlight(OperationKind.ADOPT, ADOPT_TARGET);
        revertOp[0] = h.planAndConfirm(new PlanRequest(new GroupRef(Provider.AWS, 7L, FakeAws.REGION + "/web-asg", null),
                OperationKind.REVERT, null));
        assertThat(h.jdbc.queryForObject("SELECT cancel_requested FROM spot_operation WHERE id = ?", Boolean.class,
                h.enableOpId)).as("the revert preempted the ADOPT").isTrue();
        h.store.claimForRun(h.enableOpId);
        h.runner.runClaimed(h.lease, h.enableOpId);
        assertThat(h.jdbc.queryForObject("SELECT status FROM spot_operation WHERE id = ?", String.class, h.enableOpId))
                .isEqualTo("CANCELLED");
        return h;
    }
    /**
     * Review follow-up 4: a confirmed REVERT's step 0 accepts only the fingerprint form the plan was hashed with
     * (ownership from the plan's target, as PlanService and ConfirmService do); the other form is PLAN_STALE.
     */
    @Test
    void aConfirmedRevertWhosePlanFingerprintIsInTheOtherOwnershipFormIsStaleAtStepZero() {
        try (AwsOpsHarness h = new AwsOpsHarness(three(imp(100, 200)), GroupState.ACTIVE, "MANAGED", ADOPT_TARGET,
                null, null, null)) {
            h.linkSnapshot(FakeAws.asg("web-asg").build());
            UUID revert = h.planAndConfirm(new PlanRequest(new GroupRef(Provider.AWS, 7L, FakeAws.REGION + "/web-asg",
                    null), OperationKind.REVERT, null));
            UUID planId = h.jdbc.queryForObject("SELECT plan_id FROM spot_operation WHERE id = ?", UUID.class, revert);
            String stored = h.jdbc.queryForObject("SELECT live_fingerprint FROM spot_plan WHERE id = ?", String.class,
                    planId);
            AwsGroupFacts facts = AwsGroupFacts.from(FakeAws.REGION, h.f.group());
            String other = stored.equals(AwsFingerprint.of(facts, true)) ? AwsFingerprint.of(facts, false)
                    : AwsFingerprint.of(facts, true);
            assertThat(other).isNotEqualTo(stored);
            h.jdbc.update("UPDATE spot_plan SET live_fingerprint = ? WHERE id = ?", other, planId);
            assertThat(h.runToEnd(revert)).isEqualTo("FAILED");
            assertThat(h.jdbc.queryForObject("SELECT error_code FROM spot_operation WHERE id = ?", String.class, revert))
                    .isEqualTo("PLAN_STALE");
            assertThat(h.f.writeCalls()).as("nothing was changed").isEmpty();
        }
    }
    @Test
    void aRevertThatPreemptsAnAdoptWhichSent100And200ClearsThePolicyWithMinusOneAndMinusOne() {
        UUID[] revertOp = new UUID[1];
        try (AwsOpsHarness h = adoptThenRevert(imp(100, 200), revertOp)) {
            assertThat(h.runToEnd(revertOp[0])).isEqualTo("SUCCEEDED");
            assertThat(h.groupState()).isEqualTo("REVERTED");
            assertThat(h.f.updates).anySatisfy(r -> {
                assertThat(r.instanceMaintenancePolicy()).isNotNull();
                assertThat(r.instanceMaintenancePolicy().minHealthyPercentage()).isEqualTo(-1);
                assertThat(r.instanceMaintenancePolicy().maxHealthyPercentage()).isEqualTo(-1);
            });
            assertThat(h.f.group().instanceMaintenancePolicy()).as("absent again").isNull();
        }
    }
    @Test
    void aRevertThatPreemptsAnAdoptKeepsAPolicyTheCustomerEditedAfterTheAdopt() {
        UUID[] revertOp = new UUID[1];
        try (AwsOpsHarness h = adoptThenRevert(imp(90, 150), revertOp)) {
            assertThat(h.runToEnd(revertOp[0])).isEqualTo("SUCCEEDED");
            assertThat(h.f.updates).noneMatch(r -> r.instanceMaintenancePolicy() != null);
            assertThat(h.f.group().instanceMaintenancePolicy()).isEqualTo(imp(90, 150));
        }
    }

    // ---- D-23b-01: ownership markers in desired_config after a revert ----
    private static final String OWNED = ",\"instanceMaintenancePolicy\":{\"owned\":true,\"min\":100,\"max\":200}";
    /** The plan options PlanService builds for the persisted group row. */
    static PlanOptions optionsFor(AwsOpsHarness h, Boolean allowBurstable) {
        com.xammer.cloud.domain.autospotting.SpotGroup row = h.ctx.getBean(
                com.xammer.cloud.repository.autospotting.SpotGroupRepository.class).findById(h.f.groupId).orElseThrow();
        return PlanOptions.forGroup(allowBurstable, row);
    }
    /** The real lifecycle move a new ENABLE plan makes (REVERTED -> PLANNED, ENABLE_PLAN_CREATED). */
    static void toPlanned(AwsOpsHarness h) {
        com.xammer.cloud.domain.autospotting.SpotGroup row = h.ctx.getBean(
                com.xammer.cloud.repository.autospotting.SpotGroupRepository.class).findById(h.f.groupId).orElseThrow();
        assertThat(h.ctx.getBean(com.xammer.cloud.service.autospotting.core.lifecycle.GroupLifecycleService.class)
                .transitionUnderRowLock(row, com.xammer.cloud.service.autospotting.core.lifecycle.GroupLifecycleService
                        .TransitionRequest.of(GroupState.PLANNED, "ENABLE_PLAN_CREATED",
                                com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter.Actor.system(),
                                null, h.f.clock.instant())).name()).isEqualTo("APPLIED");
    }
    /**
     * XamOps enabled the group (policy absent before, so it owned 100/200) and the REVERT op restores it. Then the
     * customer sets {@code customer} (null: none) and a new ENABLE is planned from the persisted row, confirmed and
     * run. The live policy must end as {@code expected}, and a customer policy must never be in the target.
     */
    static void reEnableAfterRevert(InstanceMaintenancePolicy customer, InstanceMaintenancePolicy expected) {
        AutoScalingGroup live = converted(List.of(override("m5.large", null), override("m5a.large", null),
                override("m6i.large", null)), imp(100, 200));
        String desired = desiredWith(List.of("m5.large", "m5a.large", "m6i.large"), OWNED);
        try (AwsOpsHarness h = new AwsOpsHarness(live, FakeAws.asg("web-asg").build(), GroupState.ACTIVE, desired)) {
            h.runner.runClaimed(h.lease, h.f.opId);
            assertThat(h.runToEnd(h.f.opId)).as("revert error " + h.opError()).isEqualTo("SUCCEEDED");
            assertThat(h.groupState()).isEqualTo("REVERTED");
            assertThat(h.f.group().instanceMaintenancePolicy()).as("the revert cleared the policy").isNull();
            assertThat(h.jdbc.queryForObject("SELECT jsonb_exists(desired_config, 'instanceMaintenancePolicy') FROM spot_group"
                    + " WHERE id = ?", Boolean.class, h.f.groupId)).as("the revert dropped the ownership marker").isFalse();
            if (customer != null) {
                h.f.replaceGroup(h.f.group().toBuilder().instanceMaintenancePolicy(customer).build());
            }
            toPlanned(h);
            PlanRequest enable = new PlanRequest(new GroupRef(Provider.AWS, 7L, FakeAws.REGION + "/web-asg", null),
                    OperationKind.ENABLE, new Profile("aggressive", null), optionsFor(h, null));
            var plan = h.provider.createPlan(enable, h.f.aws.view());
            assertThat(plan.eligibility().blockers()).as("blockers").isEmpty();
            var target = h.f.json(plan.targetJson());
            var policyRows = new ArrayList<String>();
            h.f.json(plan.diffJson()).forEach(d -> {
                if (AwsMaintenancePolicy.KEY.equals(d.path("field").asText())) {
                    policyRows.add(d.path("from").asText() + " -> " + d.path("to").asText());
                }
            });
            if (customer != null) {
                assertThat(target.has(AwsMaintenancePolicy.KEY)).as("a customer policy is never in the target").isFalse();
                assertThat(policyRows).as("the policy diff row changes nothing")
                        .containsExactly("min " + customer.minHealthyPercentage() + " / max "
                                + customer.maxHealthyPercentage() + " -> unchanged");
                assertThat(plan.eligibility().warnings().stream().map(w -> w.code()))
                        .contains("IMP_MIN_HEALTHY_LOW").doesNotContain("IMP_OVERSHOOT");
            } else {
                assertThat(AwsMaintenancePolicy.owns(target)).as("an absent policy is XamOps' again").isTrue();
                assertThat(policyRows).containsExactly("none -> min 100 / max 200");
            }
            for (int i = 1; i <= 3; i++) {
                h.f.spotInstances.add("i-" + (5 - i));
            }
            int[] updatesBeforeReEnable = {h.f.updates.size()};
            UUID op = h.planAndConfirm(enable);
            assertThat(h.runToEnd(op)).as("enable error " + h.jdbc.queryForList(
                    "SELECT error_code FROM spot_operation WHERE id = ?", String.class, op)).isEqualTo("SUCCEEDED");
            assertThat(h.groupState()).isEqualTo("ACTIVE");
            InstanceMaintenancePolicy after = h.f.group().instanceMaintenancePolicy();
            assertThat(after).as("live policy after the re-enable").isNotNull();
            assertThat(after.minHealthyPercentage()).isEqualTo(expected.minHealthyPercentage());
            assertThat(after.maxHealthyPercentage()).isEqualTo(expected.maxHealthyPercentage());
            if (customer != null) {
                // Every update after the revert (the re-enable's) must leave the customer's policy out of the request.
                int revertUpdates = updatesBeforeReEnable[0];
                assertThat(h.f.updates.subList(revertUpdates, h.f.updates.size()))
                        .as("the re-enable sent updates").isNotEmpty()
                        .allSatisfy(r -> assertThat(r.instanceMaintenancePolicy())
                                .as("no re-enable update carries a maintenance policy").isNull());
            }
        }
    }
    // D-23b-01 as found live (enable, revert, the customer sets 90/120, a confirmed re-enable keeps it) runs as the
    // AwsMaintenancePolicyMatrixIT row "ENABLE after REVERT / CUSTOMER 90/120"; the ABSENT row is beside it (review #9).
    /**
     * D-23b-01, burstable: XamOps added t3a.micro, the revert removed it, the customer then added it themselves.
     * A later plan with the option off must keep it, because it is no longer one XamOps added.
     */
    @Test
    void aTypeTheCustomerAddsAfterARevertIsNotRemovedAsOneXamOpsAdded() {
        AutoScalingGroup live = converted(List.of(T3_MICRO, T3A_MICRO), null);
        AutoScalingGroup original = converted(List.of(T3_MICRO), null).toBuilder()
                .tags(List.of()).capacityRebalance(false).build();
        String desired = desiredWith(List.of("t3.micro", "t3a.micro"),
                ",\"options\":{\"allowBurstable\":true,\"burstableAdded\":[\"t3a.micro\"]}");
        try (AwsOpsHarness h = new AwsOpsHarness(live, original, GroupState.ACTIVE, desired, true,
                fx -> fx.aws.burstableWorld())) {
            h.runner.runClaimed(h.lease, h.f.opId);
            assertThat(h.runToEnd(h.f.opId)).as("revert error " + h.opError()).isEqualTo("SUCCEEDED");
            assertThat(h.groupState()).isEqualTo("REVERTED");
            assertThat(h.jdbc.queryForObject("SELECT jsonb_exists(desired_config->'options', 'burstableAdded') FROM spot_group"
                    + " WHERE id = ?", Boolean.class, h.f.groupId)).as("the revert dropped burstableAdded").isFalse();
            // The customer adds t3a.micro by hand.
            h.f.replaceGroup(converted(List.of(T3_MICRO, T3A_MICRO), null).toBuilder().tags(List.of())
                    .capacityRebalance(false).build());
            toPlanned(h);
            var plan = h.provider.createPlan(new PlanRequest(new GroupRef(Provider.AWS, 7L,
                    FakeAws.REGION + "/web-asg", null), OperationKind.ENABLE, new Profile("aggressive", null),
                    optionsFor(h, false)), h.f.aws.view());
            var target = h.f.json(plan.targetJson());
            List<String> types = new ArrayList<>();
            target.path("overrides").forEach(n -> types.add(n.asText()));
            assertThat(types).as("the customer's own t3a.micro stays").contains("t3.micro", "t3a.micro");
            assertThat(target.path("options").has("burstableRemoved")).isFalse();
        }
    }

    private static com.xammer.cloud.domain.autospotting.SpotGroup row(AwsOpsHarness h) {
        return h.ctx.getBean(com.xammer.cloud.repository.autospotting.SpotGroupRepository.class)
                .findById(h.f.groupId).orElseThrow();
    }
    private static List<String> liveTypes(AwsOpsHarness h) {
        List<String> out = new ArrayList<>();
        var mip = h.f.group().mixedInstancesPolicy();
        if (mip != null && mip.launchTemplate() != null) {
            mip.launchTemplate().overrides().forEach(o -> out.add(o.instanceType()));
        }
        return out;
    }
    private static void simulate(AwsOpsHarness h, boolean deny) {
        org.mockito.Mockito.reset(h.f.aws.iam);
        if (deny) {
            org.mockito.Mockito.doThrow(software.amazon.awssdk.awscore.exception.AwsServiceException.builder()
                            .statusCode(403).awsErrorDetails(software.amazon.awssdk.awscore.exception.AwsErrorDetails
                                    .builder().errorCode("AccessDenied").build()).build())
                    .when(h.f.aws.iam).simulatePrincipalPolicy(org.mockito.ArgumentMatchers.any(
                            software.amazon.awssdk.services.iam.model.SimulatePrincipalPolicyRequest.class));
            return;
        }
        org.mockito.Mockito.doAnswer(inv -> {
            software.amazon.awssdk.services.iam.model.SimulatePrincipalPolicyRequest r = inv.getArgument(0);
            return software.amazon.awssdk.services.iam.model.SimulatePrincipalPolicyResponse.builder()
                    .evaluationResults(r.actionNames().stream().map(a -> software.amazon.awssdk.services.iam.model
                            .EvaluationResult.builder().evalActionName(a).evalDecision("allowed").build()).toList())
                    .build();
        }).when(h.f.aws.iam).simulatePrincipalPolicy(org.mockito.ArgumentMatchers.any(
                software.amazon.awssdk.services.iam.model.SimulatePrincipalPolicyRequest.class));
    }
    /**
     * Review #1-#3, #9: a drain-only revert (the permission check failed, so no exact restore) leaves XamOps' mixed
     * policy, its types and its 100/200 on the group. Its markers must stay: the re-enable still owns the policy and,
     * with the burstable option off, removes the type XamOps added; it keeps the original snapshot (D-23b-03), so the
     * final exact revert restores the true original, including clearing 100/200.
     */
    @Test
    void aDrainOnlyRevertKeepsXamOpsClaimsSoTheReEnableAndTheExactRevertActOnTheTrueOriginal() {
        AutoScalingGroup live = converted(List.of(T3_MICRO, T3A_MICRO), imp(100, 200));
        String desired = desiredWith(List.of("t3.micro", "t3a.micro"), OWNED
                + ",\"options\":{\"allowBurstable\":true,\"burstableAdded\":[\"t3a.micro\"]}");
        AutoScalingGroup original = FakeAws.asg("web-asg").build();
        AwsOpsHarness[] ref = new AwsOpsHarness[1];
        try (AwsOpsHarness h = new AwsOpsHarness(live, original, GroupState.ACTIVE, desired, true, fx -> {
            fx.aws.burstableWorld();
            org.mockito.Mockito.doThrow(software.amazon.awssdk.awscore.exception.AwsServiceException.builder()
                            .statusCode(403).awsErrorDetails(software.amazon.awssdk.awscore.exception.AwsErrorDetails
                                    .builder().errorCode("AccessDenied").build()).build())
                    .when(fx.aws.iam).simulatePrincipalPolicy(org.mockito.ArgumentMatchers.any(
                            software.amazon.awssdk.services.iam.model.SimulatePrincipalPolicyRequest.class));
        })) {
            ref[0] = h;
            assertThat(h.f.json(h.plan.targetJson()).path("mode").asText()).as("the revert is drain-only")
                    .isEqualTo("DRAIN_ONLY");
            h.runner.runClaimed(h.lease, h.f.opId);
            assertThat(h.runToEnd(h.f.opId)).as("drain-only revert error " + h.opError()).isEqualTo("SUCCEEDED");
            assertThat(h.groupState()).isEqualTo("REVERTED");
            assertThat(h.f.group().mixedInstancesPolicy()).as("the mixed policy stays").isNotNull();
            assertThat(h.f.group().instanceMaintenancePolicy()).as("XamOps' policy stays").isEqualTo(imp(100, 200));
            var stored = row(h);
            assertThat(AwsMaintenancePolicy.owns(stored.getDesiredConfig())).as("policy claim kept").isTrue();
            assertThat(h.f.json(stored.getDesiredConfig()).path("options").path("burstableAdded").toString())
                    .as("burstableAdded kept").isEqualTo("[\"t3a.micro\"]");
            assertThat(h.f.json(stored.getDesiredConfig()).path(PlanOptions.CONFIG_ON_GROUP).asBoolean())
                    .as("the row records that XamOps' configuration is still on the group").isTrue();

            simulate(h, false); // the stack was updated: permissions are fine again
            toPlanned(h);
            PlanOptions options = optionsFor(h, false);
            assertThat(options.underManagement()).isTrue();
            PlanRequest enable = new PlanRequest(new GroupRef(Provider.AWS, 7L, FakeAws.REGION + "/web-asg", null),
                    OperationKind.ENABLE, new Profile("aggressive", null), options);
            var plan = h.provider.createPlan(enable, h.f.aws.view());
            var target = h.f.json(plan.targetJson());
            assertThat(AwsMaintenancePolicy.owns(target)).as("the re-enable still owns XamOps' policy").isTrue();
            List<String> types = new ArrayList<>();
            target.path("overrides").forEach(n -> types.add(n.asText()));
            assertThat(types).as("option off removes the type XamOps added").doesNotContain("t3a.micro");
            assertThat(target.path("options").path("burstableRemoved").toString()).isEqualTo("[\"t3a.micro\"]");
            for (int i = 1; i <= 3; i++) {
                h.f.spotInstances.add("i-" + (5 - i));
            }
            UUID op = h.planAndConfirm(enable);
            assertThat(h.runToEnd(op)).as("re-enable error " + h.jdbc.queryForList(
                    "SELECT error_code FROM spot_operation WHERE id = ?", String.class, op)).isEqualTo("SUCCEEDED");
            assertThat(h.groupState()).isEqualTo("ACTIVE");
            assertThat(liveTypes(h)).as("t3a.micro removed on the group").doesNotContain("t3a.micro");
            assertThat(h.f.group().instanceMaintenancePolicy()).isEqualTo(imp(100, 200));
            assertThat(h.f.json(row(h).getDesiredConfig()).has(PlanOptions.CONFIG_ON_GROUP))
                    .as("the new desired_config is the re-enable's target").isFalse();
            assertThat(h.jdbc.queryForObject("SELECT count(*) FROM spot_group_snapshot WHERE group_id = ?",
                    Integer.class, h.f.groupId)).as("D-23b-03: the re-enable kept the original snapshot").isEqualTo(1);
            assertThat(h.f.json(row(h).getDesiredConfig()).has(AwsSpotProvider.KEEP_LINKED_SNAPSHOT))
                    .as("review #6: the step instruction is not stored in desired_config").isFalse();
            h.f.spotInstances.clear(); // the final revert drains Spot first
            UUID revert = h.planAndConfirm(new PlanRequest(new GroupRef(Provider.AWS, 7L,
                    FakeAws.REGION + "/web-asg", null), OperationKind.REVERT, null, PlanOptions.none()));
            assertThat(h.runToEnd(revert)).as("final revert error " + h.jdbc.queryForList(
                    "SELECT error_code FROM spot_operation WHERE id = ?", String.class, revert)).isEqualTo("SUCCEEDED");
            assertThat(h.groupState()).isEqualTo("REVERTED");
            assertThat(h.f.group().instanceMaintenancePolicy()).as("the final revert clears 100/200").isNull();
            assertThat(h.f.group().mixedInstancesPolicy()).as("exact restore").isNull();
            assertThat(AwsMaintenancePolicy.owns(row(h).getDesiredConfig())).as("claim released").isFalse();

        }
    }
    /**
     * Review #7, #9: a revert that ends FAILED after its restore step already cleared XamOps' policy must not keep
     * claiming it, so a policy the customer sets afterwards is read as theirs.
     */
    @Test
    void aRevertThatFailsAfterItsRestoreReleasesTheClaimOnThePolicyItCleared() {
        AutoScalingGroup live = converted(List.of(override("m5.large", null), override("m5a.large", null),
                override("m6i.large", null)), imp(100, 200));
        String desired = desiredWith(List.of("m5.large", "m5a.large", "m6i.large"), OWNED);
        try (AwsOpsHarness h = new AwsOpsHarness(live, FakeAws.asg("web-asg").build(), GroupState.ACTIVE, desired)) {
            org.mockito.Mockito.doAnswer(inv -> {
                UpdateAutoScalingGroupRequest r = inv.getArgument(0);
                h.f.log.add("update");
                h.f.updates.add(r);
                h.f.applyUpdate(r);
                if (r.launchTemplate() != null) {
                    // Someone turns Capacity Rebalancing on right after the restore: verification fails terminally.
                    h.f.replaceGroup(h.f.group().toBuilder().capacityRebalance(true).build());
                }
                return software.amazon.awssdk.services.autoscaling.model.UpdateAutoScalingGroupResponse.builder().build();
            }).when(h.f.write).updateAutoScalingGroup(org.mockito.ArgumentMatchers.any(UpdateAutoScalingGroupRequest.class));
            // One Spot instance makes the drain wait once, so the restore runs in a later pass (as it does live).
            h.f.spotInstances.add("i-4");
            h.runner.runClaimed(h.lease, h.f.opId);
            h.f.spotInstances.clear();
            assertThat(h.runToEnd(h.f.opId)).as("revert error " + h.opError()).isEqualTo("FAILED");
            assertThat(h.groupState()).as("revert error " + h.opError()).isEqualTo("FAILED");
            assertThat(h.f.group().instanceMaintenancePolicy()).as("the restore cleared XamOps' policy").isNull();
            assertThat(AwsMaintenancePolicy.owns(row(h).getDesiredConfig())).as("claim released").isFalse();
            h.f.replaceGroup(h.f.group().toBuilder().instanceMaintenancePolicy(imp(90, 120)).build());
            assertThat(AwsMaintenancePolicy.owns(PlanOptions.ownershipConfigJson(row(h))))
                    .as("the customer's 90/120 is read as theirs").isFalse();
        }
    }
    /** Review #7 counterpart: a revert that fails before restoring keeps the claim (the policy is still XamOps'). */
    @Test
    void aRevertThatFailsBeforeItsRestoreKeepsTheClaim() {
        AutoScalingGroup live = converted(List.of(override("m5.large", null), override("m5a.large", null),
                override("m6i.large", null)), imp(100, 200));
        String desired = desiredWith(List.of("m5.large", "m5a.large", "m6i.large"), OWNED);
        try (AwsOpsHarness h = new AwsOpsHarness(live, FakeAws.asg("web-asg").build(), GroupState.ACTIVE, desired)) {
            org.mockito.Mockito.doAnswer(inv -> {
                UpdateAutoScalingGroupRequest r = inv.getArgument(0);
                if (r.launchTemplate() != null) {
                    throw software.amazon.awssdk.awscore.exception.AwsServiceException.builder().statusCode(400)
                            .awsErrorDetails(software.amazon.awssdk.awscore.exception.AwsErrorDetails.builder()
                                    .errorCode("AccessDenied").build()).build();
                }
                h.f.log.add("update");
                h.f.updates.add(r);
                h.f.applyUpdate(r);
                return software.amazon.awssdk.services.autoscaling.model.UpdateAutoScalingGroupResponse.builder().build();
            }).when(h.f.write).updateAutoScalingGroup(org.mockito.ArgumentMatchers.any(UpdateAutoScalingGroupRequest.class));
            // One Spot instance makes the drain wait once, so the restore runs in a later pass (as it does live).
            h.f.spotInstances.add("i-4");
            h.runner.runClaimed(h.lease, h.f.opId);
            h.f.spotInstances.clear();
            assertThat(h.runToEnd(h.f.opId)).as("revert error " + h.opError()).isEqualTo("FAILED");
            assertThat(h.groupState()).as("revert error " + h.opError()).isEqualTo("FAILED");
            assertThat(h.f.group().instanceMaintenancePolicy()).as("XamOps' policy is still live").isEqualTo(imp(100, 200));
            assertThat(AwsMaintenancePolicy.owns(row(h).getDesiredConfig())).as("claim kept").isTrue();
            assertThat(AwsMaintenancePolicy.owns(PlanOptions.ownershipConfigJson(row(h)))).isTrue();
        }
    }

    /**
     * D-23b-02: a REVERT that mutates (drain, restore) and then fails terminally in the same runner pass ends FAILED
     * after mutation, not FAILED_BEFORE_MUTATION with the group sent back to its prior state.
     */
    @Test
    void aRevertThatMutatesAndFailsTerminallyInOnePassEndsFailedNotBeforeMutation() {
        AutoScalingGroup live = converted(List.of(override("m5.large", null), override("m5a.large", null),
                override("m6i.large", null)), imp(100, 200));
        String desired = desiredWith(List.of("m5.large", "m5a.large", "m6i.large"), OWNED);
        try (AwsOpsHarness h = new AwsOpsHarness(live, FakeAws.asg("web-asg").build(), GroupState.ACTIVE, desired)) {
            org.mockito.Mockito.doAnswer(inv -> {
                UpdateAutoScalingGroupRequest r = inv.getArgument(0);
                h.f.log.add("update");
                h.f.updates.add(r);
                h.f.applyUpdate(r);
                if (r.launchTemplate() != null) {
                    h.f.replaceGroup(h.f.group().toBuilder().capacityRebalance(true).build());
                }
                return software.amazon.awssdk.services.autoscaling.model.UpdateAutoScalingGroupResponse.builder().build();
            }).when(h.f.write).updateAutoScalingGroup(org.mockito.ArgumentMatchers.any(UpdateAutoScalingGroupRequest.class));
            h.runner.runClaimed(h.lease, h.f.opId); // drain, restore and the failing verification in one pass
            assertThat(h.jdbc.queryForObject("SELECT status FROM spot_operation WHERE id = ?", String.class, h.f.opId))
                    .as("ended in the first pass").isEqualTo("FAILED");
            assertThat(h.opError()).isEqualTo("REVERT_MISMATCH");
            assertThat(h.f.updates).as("the cloud was changed").isNotEmpty();
            assertThat(h.groupState()).as("a revert that changed the group ends FAILED").isEqualTo("FAILED");
            assertThat(h.jdbc.queryForList("SELECT reason_code FROM spot_group_transition WHERE group_id = ?",
                    String.class, h.f.groupId)).contains("REVERT_FAILED").doesNotContain("FAILED_BEFORE_MUTATION");
        }
    }

    /** Drain-only revert of the burstable group used above; returns the harness with the group REVERTED. */
    private static AwsOpsHarness drainOnlyReverted() {
        AutoScalingGroup live = converted(List.of(T3_MICRO, T3A_MICRO), imp(100, 200));
        String desired = desiredWith(List.of("t3.micro", "t3a.micro"), OWNED
                + ",\"options\":{\"allowBurstable\":true,\"burstableAdded\":[\"t3a.micro\"]}");
        AwsOpsHarness h = new AwsOpsHarness(live, FakeAws.asg("web-asg").build(), GroupState.ACTIVE, desired, true, fx -> {
            fx.aws.burstableWorld();
            org.mockito.Mockito.doThrow(software.amazon.awssdk.awscore.exception.AwsServiceException.builder()
                            .statusCode(403).awsErrorDetails(software.amazon.awssdk.awscore.exception.AwsErrorDetails
                                    .builder().errorCode("AccessDenied").build()).build())
                    .when(fx.aws.iam).simulatePrincipalPolicy(org.mockito.ArgumentMatchers.any(
                            software.amazon.awssdk.services.iam.model.SimulatePrincipalPolicyRequest.class));
        });
        h.runner.runClaimed(h.lease, h.f.opId);
        assertThat(h.runToEnd(h.f.opId)).as("drain-only revert error " + h.opError()).isEqualTo("SUCCEEDED");
        assertThat(h.groupState()).isEqualTo("REVERTED");
        simulate(h, false);
        return h;
    }
    /**
     * D-23b-03: a policy the customer sets between the drain-only revert and the re-enable is theirs: the re-enable
     * neither targets nor sends 100/200, the plan says so, and the final revert keeps 90/120.
     */
    @Test
    void aPolicyTheCustomerSetsAfterADrainOnlyRevertIsKeptByTheReEnableAndTheFinalRevert() {
        try (AwsOpsHarness h = drainOnlyReverted()) {
            h.f.replaceGroup(h.f.group().toBuilder().instanceMaintenancePolicy(imp(90, 120)).build());
            toPlanned(h);
            PlanRequest enable = new PlanRequest(new GroupRef(Provider.AWS, 7L, FakeAws.REGION + "/web-asg", null),
                    OperationKind.ENABLE, new Profile("aggressive", null), optionsFor(h, false));
            var plan = h.provider.createPlan(enable, h.f.aws.view());
            assertThat(h.f.json(plan.targetJson()).has(AwsMaintenancePolicy.KEY)).as("not in the target").isFalse();
            var kept = plan.eligibility().warnings().stream().filter(w -> w.code().equals("ORIGINAL_SNAPSHOT_KEPT"))
                    .findFirst().orElseThrow();
            assertThat(kept.text()).contains("maintenance policy changed").contains("XamOps keeps it");
            assertThat(plan.eligibility().warnings().stream().map(w -> w.code())).contains("IMP_MIN_HEALTHY_LOW");
            for (int i = 1; i <= 3; i++) {
                h.f.spotInstances.add("i-" + (5 - i));
            }
            int before = h.f.updates.size();
            UUID op = h.planAndConfirm(enable);
            assertThat(h.runToEnd(op)).isEqualTo("SUCCEEDED");
            assertThat(h.f.updates.subList(before, h.f.updates.size()))
                    .allSatisfy(r -> assertThat(r.instanceMaintenancePolicy()).isNull());
            assertThat(h.f.group().instanceMaintenancePolicy()).isEqualTo(imp(90, 120));
            h.f.spotInstances.clear();
            UUID revert = h.planAndConfirm(new PlanRequest(new GroupRef(Provider.AWS, 7L,
                    FakeAws.REGION + "/web-asg", null), OperationKind.REVERT, null, PlanOptions.none()));
            assertThat(h.runToEnd(revert)).as("final revert error " + h.opError()).isEqualTo("SUCCEEDED");
            assertThat(h.f.group().mixedInstancesPolicy()).as("exact restore of the original").isNull();
            assertThat(h.f.group().instanceMaintenancePolicy()).as("the customer's policy stays").isEqualTo(imp(90, 120));
        }
    }
    /**
     * D-23b-03: another XamOps-owned field the customer changes after the drain-only revert (here an override they
     * add) is surfaced in the plan's acknowledged warning, because the final revert restores the original for it.
     */
    @Test
    void anOwnedFieldTheCustomerChangesAfterADrainOnlyRevertIsListedInThePlan() {
        try (AwsOpsHarness h = drainOnlyReverted()) {
            var mip = h.f.group().mixedInstancesPolicy();
            List<LaunchTemplateOverrides> more = new ArrayList<>(mip.launchTemplate().overrides());
            more.add(override("t3a.small", null));
            h.f.replaceGroup(h.f.group().toBuilder().mixedInstancesPolicy(mip.toBuilder().launchTemplate(
                    mip.launchTemplate().toBuilder().overrides(more).build()).build()).build());
            toPlanned(h);
            var plan = h.provider.createPlan(new PlanRequest(new GroupRef(Provider.AWS, 7L,
                    FakeAws.REGION + "/web-asg", null), OperationKind.ENABLE, new Profile("aggressive", null),
                    optionsFor(h, null)), h.f.aws.view());
            var kept = plan.eligibility().warnings().stream().filter(w -> w.code().equals("ORIGINAL_SNAPSHOT_KEPT"))
                    .findFirst().orElseThrow();
            assertThat(kept.id()).isEqualTo("w-original-snapshot-kept");
            assertThat(kept.text()).contains("Changed on the group since that revert: overrides")
                    .contains("restores the original for them");
            assertThat(AwsMaintenancePolicy.owns(h.f.json(plan.targetJson()))).as("100/200 still XamOps'").isTrue();
            assertThat(h.f.json(plan.targetJson()).path(AwsSpotProvider.KEEP_LINKED_SNAPSHOT).asBoolean()).isTrue();
            // Review #7: the promise holds through both operations.
            UUID snapshotBefore = row(h).getSnapshotId();
            for (int i = 1; i <= 3; i++) {
                h.f.spotInstances.add("i-" + (5 - i));
            }
            UUID op = h.planAndConfirm(new PlanRequest(new GroupRef(Provider.AWS, 7L, FakeAws.REGION + "/web-asg",
                    null), OperationKind.ENABLE, new Profile("aggressive", null), optionsFor(h, null)));
            assertThat(h.runToEnd(op)).as("re-enable error " + h.jdbc.queryForList(
                    "SELECT error_code FROM spot_operation WHERE id = ?", String.class, op)).isEqualTo("SUCCEEDED");
            assertThat(liveTypes(h)).as("the target is applied on top of the customer's override").contains("t3a.small");
            assertThat(row(h).getSnapshotId()).as("the linked original is unchanged").isEqualTo(snapshotBefore);
            assertThat(h.jdbc.queryForObject("SELECT count(*) FROM spot_group_snapshot WHERE group_id = ?",
                    Integer.class, h.f.groupId)).isEqualTo(1);
            h.f.spotInstances.clear();
            UUID revert = h.planAndConfirm(new PlanRequest(new GroupRef(Provider.AWS, 7L,
                    FakeAws.REGION + "/web-asg", null), OperationKind.REVERT, null, PlanOptions.none()));
            assertThat(h.runToEnd(revert)).as("final revert error " + h.opError()).isEqualTo("SUCCEEDED");
            assertThat(h.f.group().mixedInstancesPolicy()).as("the saved original (launch template only)").isNull();
            assertThat(h.f.group().instanceMaintenancePolicy()).as("100/200 cleared").isNull();
        }
    }
    /** Review #2: a share above base the customer set after the drain (not the drained 100) is listed. */
    @Test
    void aCustomerChangeToTheOnDemandShareAfterADrainOnlyRevertIsListedInThePlan() {
        try (AwsOpsHarness h = drainOnlyReverted()) {
            var mip = h.f.group().mixedInstancesPolicy();
            assertThat(mip.instancesDistribution().onDemandPercentageAboveBaseCapacity()).as("drained").isEqualTo(100);
            toPlanned(h);
            PlanRequest enable = new PlanRequest(new GroupRef(Provider.AWS, 7L, FakeAws.REGION + "/web-asg", null),
                    OperationKind.ENABLE, new Profile("aggressive", null), optionsFor(h, null));
            String drainedText = h.provider.createPlan(enable, h.f.aws.view()).eligibility().warnings().stream()
                    .filter(w -> w.code().equals("ORIGINAL_SNAPSHOT_KEPT")).findFirst().orElseThrow().text();
            assertThat(drainedText).as("the drain's own 100 is not a customer change").doesNotContain("Changed on the group");
            h.f.replaceGroup(h.f.group().toBuilder().mixedInstancesPolicy(mip.toBuilder().instancesDistribution(
                    mip.instancesDistribution().toBuilder().onDemandPercentageAboveBaseCapacity(70).build()).build())
                    .build());
            String text = h.provider.createPlan(enable, h.f.aws.view()).eligibility().warnings().stream()
                    .filter(w -> w.code().equals("ORIGINAL_SNAPSHOT_KEPT")).findFirst().orElseThrow().text();
            assertThat(text).contains("Changed on the group since that revert: onDemandPercentageAboveBaseCapacity");
        }
    }
    /** Review #1, plan side: with no linked snapshot the plan makes no promise (no instruction, no warning). */
    @Test
    void withoutALinkedSnapshotTheEnableAfterADrainOnlyRevertMakesNoKeptOriginalPromise() {
        try (AwsOpsHarness h = drainOnlyReverted()) {
            h.jdbc.update("UPDATE spot_group SET snapshot_id = NULL WHERE id = ?", h.f.groupId);
            toPlanned(h);
            var plan = h.provider.createPlan(new PlanRequest(new GroupRef(Provider.AWS, 7L,
                    FakeAws.REGION + "/web-asg", null), OperationKind.ENABLE, new Profile("aggressive", null),
                    optionsFor(h, null)), h.f.aws.view());
            assertThat(plan.eligibility().warnings().stream().map(w -> w.code())).doesNotContain("ORIGINAL_SNAPSHOT_KEPT");
            assertThat(h.f.json(plan.targetJson()).has(AwsSpotProvider.KEEP_LINKED_SNAPSHOT)).isFalse();
            assertThat(AwsMaintenancePolicy.owns(h.f.json(plan.targetJson())))
                    .as("the markers still apply: XamOps' configuration is on the group").isTrue();
        }
    }
    /** Review #1, step side: the plan promised the linked original; if it is gone at run time, fail closed. */
    @Test
    void aKeptOriginalEnableWhoseLinkedSnapshotIsGoneFailsClosedWithSnapshotMissing() {
        try (AwsOpsHarness h = drainOnlyReverted()) {
            toPlanned(h);
            PlanRequest enable = new PlanRequest(new GroupRef(Provider.AWS, 7L, FakeAws.REGION + "/web-asg", null),
                    OperationKind.ENABLE, new Profile("aggressive", null), optionsFor(h, null));
            for (int i = 1; i <= 3; i++) {
                h.f.spotInstances.add("i-" + (5 - i));
            }
            int before = h.f.updates.size();
            UUID op = h.planAndConfirm(enable);
            assertThat(h.jdbc.queryForObject("SELECT target_config->>'keepLinkedSnapshot' FROM spot_operation"
                    + " WHERE id = ?", String.class, op)).isEqualTo("true");
            h.jdbc.update("UPDATE spot_group SET snapshot_id = NULL WHERE id = ?", h.f.groupId);
            assertThat(h.runToEnd(op)).isEqualTo("FAILED");
            assertThat(h.jdbc.queryForObject("SELECT error_code FROM spot_operation WHERE id = ?", String.class, op))
                    .isEqualTo("SNAPSHOT_MISSING");
            assertThat(h.f.updates.size()).as("nothing was sent").isEqualTo(before);
            assertThat(h.jdbc.queryForObject("SELECT count(*) FROM spot_group_snapshot WHERE group_id = ?",
                    Integer.class, h.f.groupId)).as("the drained group was not captured").isEqualTo(1);
        }
    }
    /** D-23b-03 counterpart: a first enable, or one after a full revert, takes a fresh snapshot and shows no warning. */
    @Test
    void anEnableAfterAFullRevertTakesAFreshSnapshotAndHasNoOriginalKeptWarning() {
        AutoScalingGroup live = converted(List.of(override("m5.large", null), override("m5a.large", null),
                override("m6i.large", null)), imp(100, 200));
        try (AwsOpsHarness h = new AwsOpsHarness(live, FakeAws.asg("web-asg").build(), GroupState.ACTIVE,
                desiredWith(List.of("m5.large", "m5a.large", "m6i.large"), OWNED))) {
            h.runner.runClaimed(h.lease, h.f.opId);
            assertThat(h.runToEnd(h.f.opId)).isEqualTo("SUCCEEDED");
            toPlanned(h);
            PlanRequest enable = new PlanRequest(new GroupRef(Provider.AWS, 7L, FakeAws.REGION + "/web-asg", null),
                    OperationKind.ENABLE, new Profile("aggressive", null), optionsFor(h, null));
            var plan = h.provider.createPlan(enable, h.f.aws.view());
            assertThat(plan.eligibility().warnings().stream().map(w -> w.code())).doesNotContain("ORIGINAL_SNAPSHOT_KEPT");
            assertThat(h.f.json(plan.targetJson()).has(AwsSpotProvider.KEEP_LINKED_SNAPSHOT)).isFalse();
            for (int i = 1; i <= 3; i++) {
                h.f.spotInstances.add("i-" + (5 - i));
            }
            UUID op = h.planAndConfirm(enable);
            assertThat(h.runToEnd(op)).isEqualTo("SUCCEEDED");
            assertThat(h.jdbc.queryForObject("SELECT count(*) FROM spot_group_snapshot WHERE group_id = ?",
                    Integer.class, h.f.groupId)).as("a fresh snapshot of the reverted group").isEqualTo(2);
        }
    }

    /** Review round 2 #4: after a drain-only revert, status resolves policy ownership exactly as planning does. */
    @Test
    void afterADrainOnlyRevertStatusTreatsACustomerPolicyAsTheirsLikePlanning() {
        try (AwsOpsHarness h = drainOnlyReverted()) {
            var owned = h.provider.getStatus(row(h), h.f.aws.view()).liveFingerprint();
            assertThat(owned).as("100/200 is still XamOps'")
                    .isEqualTo(AwsFingerprint.of(AwsGroupFacts.from(FakeAws.REGION, h.f.group()), true));
            h.f.replaceGroup(h.f.group().toBuilder().instanceMaintenancePolicy(imp(90, 120)).build());
            AwsGroupFacts facts = AwsGroupFacts.from(FakeAws.REGION, h.f.group());
            assertThat(h.provider.getStatus(row(h), h.f.aws.view()).liveFingerprint())
                    .as("the customer's 90/120 is theirs in status too").isEqualTo(AwsFingerprint.of(facts, false));
            toPlanned(h);
            var plan = h.provider.createPlan(new PlanRequest(new GroupRef(Provider.AWS, 7L,
                    FakeAws.REGION + "/web-asg", null), OperationKind.ENABLE, new Profile("aggressive", null),
                    optionsFor(h, null)), h.f.aws.view());
            assertThat(AwsMaintenancePolicy.owns(h.f.json(plan.targetJson()))).as("planning agrees").isFalse();
        }
    }
}
