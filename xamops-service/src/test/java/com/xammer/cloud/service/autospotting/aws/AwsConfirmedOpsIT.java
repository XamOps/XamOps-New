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
     * D-23b-03 option (b), the user's decision of 2026-11-25. A drain-only revert (here the permission check
     * failed, so no exact restore) leaves XamOps' mixed policy, its added type and its 100/200 on the group; the
     * markers stay (D-23b-01). A new ENABLE is blocked with CONFIG_STILL_ON_GROUP; only the exact REVERT is
     * offered. With permissions fixed it restores the original saved before the first enable (shape, no added
     * type, 100/200 cleared), and afterwards an ENABLE is allowed again and takes a fresh snapshot.
     */
    @Test
    void afterADrainOnlyRevertOnlyTheExactRevertIsOfferedAndAfterItAnEnableIsAllowedAgain() {
        try (AwsOpsHarness h = drainOnlyReverted()) {
            assertThat(h.f.group().mixedInstancesPolicy()).as("the mixed policy stays").isNotNull();
            assertThat(h.f.group().instanceMaintenancePolicy()).as("XamOps' policy stays").isEqualTo(imp(100, 200));
            var stored = row(h);
            assertThat(AwsMaintenancePolicy.owns(stored.getDesiredConfig())).as("policy claim kept").isTrue();
            assertThat(h.f.json(stored.getDesiredConfig()).path("options").path("burstableAdded").toString())
                    .as("burstableAdded kept").isEqualTo("[\"t3a.micro\"]");
            String text = assertEnableBlocked(h);
            // This drain happened because the permission check could not run (CHECK_FAILED): no PassRole advice.
            assertThat(h.f.json(row(h).getDesiredConfig()).path(PlanOptions.CONFIG_ON_GROUP_REASON).asText())
                    .isEqualTo("CHECK_FAILED");
            assertThat(text).isEqualTo("XamOps' configuration is still on this group. The last revert couldn't check its "
                    + "permissions, so it only moved the group to On-Demand. Run Revert first to restore the original.");
            assertThat(h.groupState()).isEqualTo("REVERTED");
            UUID snapshotBefore = row(h).getSnapshotId();
            h.f.spotInstances.clear();
            UUID revert = h.planAndConfirm(new PlanRequest(asgRef(), OperationKind.REVERT, null, optionsFor(h, null)));
            assertThat(h.jdbc.queryForObject("SELECT target_config->>'mode' FROM spot_operation WHERE id = ?",
                    String.class, revert)).as("the exact restore (GR-02)").isEqualTo("RESTORE_ONLY");
            assertThat(h.runToEnd(revert)).as("revert error " + h.opError()).isEqualTo("SUCCEEDED");
            assertThat(h.groupState()).isEqualTo("REVERTED");
            assertThat(h.f.group().mixedInstancesPolicy()).as("the original shape, no added type").isNull();
            assertThat(h.f.group().instanceMaintenancePolicy()).as("100/200 cleared").isNull();
            assertThat(row(h).getSnapshotId()).as("restored from the linked original").isEqualTo(snapshotBefore);
            assertThat(PlanOptions.forGroup(null, row(h)).configOnGroup()).isFalse();
            assertThat(AwsMaintenancePolicy.owns(row(h).getDesiredConfig())).as("claim released").isFalse();
            // Enable is allowed again, with a fresh snapshot of the restored group.
            toPlanned(h);
            PlanRequest enable = new PlanRequest(asgRef(), OperationKind.ENABLE, new Profile("aggressive", null),
                    optionsFor(h, null));
            assertThat(h.provider.createPlan(enable, h.f.aws.view()).eligibility().blockers()).isEmpty();
            for (int i = 1; i <= 3; i++) {
                h.f.spotInstances.add("i-" + (5 - i));
            }
            UUID op = h.planAndConfirm(enable);
            assertThat(h.runToEnd(op)).as("enable error " + h.jdbc.queryForList(
                    "SELECT error_code FROM spot_operation WHERE id = ?", String.class, op)).isEqualTo("SUCCEEDED");
            assertThat(h.groupState()).isEqualTo("ACTIVE");
            assertThat(h.jdbc.queryForObject("SELECT count(*) FROM spot_group_snapshot WHERE group_id = ?",
                    Integer.class, h.f.groupId)).as("a fresh snapshot").isEqualTo(2);
            assertThat(row(h).getSnapshotId()).isNotEqualTo(snapshotBefore);
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
    /** Option (b): a policy the customer sets after the drain-only revert is kept by the exact revert. */
    @Test
    void aPolicyTheCustomerSetsAfterADrainOnlyRevertIsKeptByTheExactRevert() {
        try (AwsOpsHarness h = drainOnlyReverted()) {
            h.f.replaceGroup(h.f.group().toBuilder().instanceMaintenancePolicy(imp(90, 120)).build());
            assertEnableBlocked(h);
            h.f.spotInstances.clear();
            UUID revert = h.planAndConfirm(new PlanRequest(asgRef(), OperationKind.REVERT, null, optionsFor(h, null)));
            assertThat(h.runToEnd(revert)).as("revert error " + h.opError()).isEqualTo("SUCCEEDED");
            assertThat(h.f.group().mixedInstancesPolicy()).as("exact restore of the original").isNull();
            assertThat(h.f.group().instanceMaintenancePolicy()).as("the customer's policy stays").isEqualTo(imp(90, 120));
        }
    }
    /**
     * Option (b), missing original: the enable is blocked with a plain message that does not send the customer to
     * the revert, because the revert is blocked too (SNAPSHOT_MISSING).
     */
    @Test
    void withoutTheSavedOriginalBothEnableAndRevertSayPlainlyThatItIsMissing() {
        try (AwsOpsHarness h = drainOnlyReverted()) {
            h.jdbc.update("UPDATE spot_group SET snapshot_id = NULL WHERE id = ?", h.f.groupId);
            String text = assertEnableBlocked(h);
            assertThat(text).contains("is missing").contains("can't restore it").contains("Contact support")
                    .doesNotContain("Revert");
            var revert = h.provider.createPlan(new PlanRequest(asgRef(), OperationKind.REVERT, null,
                    optionsFor(h, null)), h.f.aws.view());
            assertThat(revert.eligibility().blockers()).extracting(b -> b.code()).containsExactly("SNAPSHOT_MISSING");
            assertThat(revert.eligibility().blockers().get(0).text()).contains("original configuration");
        }
    }
    /** D-23b-03 counterpart: an enable after a full revert is not blocked and takes a fresh snapshot. */
    @Test
    void anEnableAfterAFullRevertTakesAFreshSnapshotAndIsNotBlocked() {
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
            assertThat(plan.eligibility().blockers()).extracting(b -> b.code())
                    .doesNotContain(AwsSpotProvider.CONFIG_STILL_ON_GROUP);
            for (int i = 1; i <= 3; i++) {
                h.f.spotInstances.add("i-" + (5 - i));
            }
            UUID op = h.planAndConfirm(enable);
            assertThat(h.runToEnd(op)).isEqualTo("SUCCEEDED");
            assertThat(h.jdbc.queryForObject("SELECT count(*) FROM spot_group_snapshot WHERE group_id = ?",
                    Integer.class, h.f.groupId)).as("a fresh snapshot of the reverted group").isEqualTo(2);
        }
    }


    private static GroupRef asgRef() {
        return new GroupRef(Provider.AWS, 7L, FakeAws.REGION + "/web-asg", null);
    }

    /** The ENABLE plan for the persisted row must carry CONFIG_STILL_ON_GROUP, and confirming it is refused. */
    private static String assertEnableBlocked(AwsOpsHarness h) {
        PlanRequest enable = new PlanRequest(asgRef(), OperationKind.ENABLE, new Profile("aggressive", null),
                optionsFor(h, null));
        var plan = h.provider.createPlan(enable, h.f.aws.view());
        var blocker = plan.eligibility().blockers().stream()
                .filter(b -> b.code().equals(AwsSpotProvider.CONFIG_STILL_ON_GROUP)).findFirst().orElseThrow();
        assertThat(h.provider.checkEligibility(asgRef(), h.f.aws.view(), new Profile("aggressive", null),
                com.xammer.cloud.service.autospotting.spi.EvalMode.PLAN, optionsFor(h, null)).blockers())
                .extracting(b -> b.code()).contains(AwsSpotProvider.CONFIG_STILL_ON_GROUP);
        // Review #1: the group list and the detail row (AwsGroupLister.buildRow) show the same blocker.
        var lookup = org.mockito.Mockito.mock(com.xammer.cloud.service.autospotting.core.GroupLookup.class);
        org.mockito.Mockito.when(lookup.forAccount(Provider.AWS, 7L)).thenReturn(List.of(row(h)));
        AwsGroupLister lister = new AwsGroupLister(h.f.views, h.f.evaluator, h.provider, new AwsGroupStatusReader(),
                lookup, java.time.Duration.ofSeconds(20), java.time.Clock.systemUTC());
        var detail = lister.row(FakeAws.account(), FakeAws.REGION + "/web-asg", row(h)).orElseThrow();
        assertThat(detail.enableAllowed()).as("detail: Enable is not offered").isFalse();
        assertThat(detail.blockers()).extracting(b -> b.code()).contains(AwsSpotProvider.CONFIG_STILL_ON_GROUP);
        var listed = lister.list(FakeAws.account()).items().stream()
                .filter(r -> r.groupKey().equals(FakeAws.REGION + "/web-asg")).findFirst().orElseThrow();
        assertThat(listed.enableAllowed()).as("list: Enable is not offered").isFalse();
        assertThat(listed.blockers()).extracting(b -> b.code()).contains(AwsSpotProvider.CONFIG_STILL_ON_GROUP);
        assertThat(PlanOptions.forGroup(null, row(h)).configOnGroup()).isTrue();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> h.planAndConfirm(enable))
                .as("a blocked enable cannot be confirmed")
                .isInstanceOfSatisfying(com.xammer.cloud.service.autospotting.core.AutoSpottingApiException.class,
                        e -> {
                            // 422 with the plan's first blocker (CONFIG_STILL_ON_GROUP, or a permission gap listed
                            // before it while PassRole is still denied).
                            assertThat(e.getStatus().value()).isEqualTo(422);
                            assertThat(plan.eligibility().blockers()).extracting(b -> b.code()).contains(e.getCode());
                        });
        // The refused plan stays OPEN until the next plan supersedes it (PlanService does that; the harness does not).
        h.jdbc.update("UPDATE spot_plan SET status = 'SUPERSEDED' WHERE group_id = ? AND status = 'OPEN'", h.f.groupId);
        return blocker.text();
    }

    /** Review #3: a drain caused by a denied iam:PassRole names the permission to grant. */
    @Test
    void afterAPassRoleDeniedDrainTheBlockerSaysToGrantPassRole() {
        AutoScalingGroup live = converted(List.of(T3_MICRO, T3A_MICRO), imp(100, 200));
        String desired = desiredWith(List.of("t3.micro", "t3a.micro"), OWNED
                + ",\"options\":{\"allowBurstable\":true,\"burstableAdded\":[\"t3a.micro\"]}");
        try (AwsOpsHarness h = new AwsOpsHarness(live, FakeAws.asg("web-asg").build(), GroupState.ACTIVE, desired, true,
                fx -> {
                    fx.aws.burstableWorld();
                    passRoleDenied(fx);
                })) {
            assertThat(h.f.json(h.plan.targetJson()).path("reasons").toString()).contains("PASSROLE_DENIED");
            h.runner.runClaimed(h.lease, h.f.opId);
            assertThat(h.runToEnd(h.f.opId)).as("drain error " + h.opError()).isEqualTo("SUCCEEDED");
            assertThat(h.f.json(row(h).getDesiredConfig()).path(PlanOptions.CONFIG_ON_GROUP_REASON).asText())
                    .isEqualTo("PASSROLE_DENIED");
            // While PassRole is still denied the enable also carries PERMISSION_MISSING; the drain's own text is
            // what matters here, so the remaining permissions are made fine.
            simulate(h, false);
            assertThat(assertEnableBlocked(h))
                    .isEqualTo("XamOps' configuration is still on this group. Grant iam:PassRole and run Revert first.");
        }
    }

    /** An instance profile on the template and a denied iam:PassRole for it (the PASSROLE_DENIED drain). */
    private static void passRoleDenied(AwsConversionFixture fx) {
        org.mockito.Mockito.doReturn(software.amazon.awssdk.services.ec2.model.DescribeLaunchTemplateVersionsResponse
                .builder().launchTemplateVersions(software.amazon.awssdk.services.ec2.model.LaunchTemplateVersion.builder()
                        .versionNumber(7L).launchTemplateData(software.amazon.awssdk.services.ec2.model
                                .ResponseLaunchTemplateData.builder().imageId("ami-0aaa").instanceType("t3.micro")
                                .iamInstanceProfile(software.amazon.awssdk.services.ec2.model
                                        .LaunchTemplateIamInstanceProfileSpecification.builder().name("web-profile").build())
                                .build()).build()).build())
                .when(fx.aws.ec2).describeLaunchTemplateVersions(org.mockito.ArgumentMatchers.any(
                        software.amazon.awssdk.services.ec2.model.DescribeLaunchTemplateVersionsRequest.class));
        org.mockito.Mockito.doReturn(software.amazon.awssdk.services.iam.model.GetInstanceProfileResponse.builder()
                        .instanceProfile(software.amazon.awssdk.services.iam.model.InstanceProfile.builder().roles(
                                software.amazon.awssdk.services.iam.model.Role.builder()
                                        .arn("arn:aws:iam::111122223333:role/web").build()).build()).build())
                .when(fx.aws.iam).getInstanceProfile(org.mockito.ArgumentMatchers.any(
                        software.amazon.awssdk.services.iam.model.GetInstanceProfileRequest.class));
        org.mockito.Mockito.doAnswer(inv -> {
            software.amazon.awssdk.services.iam.model.SimulatePrincipalPolicyRequest r = inv.getArgument(0);
            return software.amazon.awssdk.services.iam.model.SimulatePrincipalPolicyResponse.builder()
                    .evaluationResults(r.actionNames().stream().map(a -> software.amazon.awssdk.services.iam.model
                            .EvaluationResult.builder().evalActionName(a)
                            .evalDecision("iam:PassRole".equals(a) ? "implicitDeny" : "allowed").build()).toList())
                    .build();
        }).when(fx.aws.iam).simulatePrincipalPolicy(org.mockito.ArgumentMatchers.any(
                software.amazon.awssdk.services.iam.model.SimulatePrincipalPolicyRequest.class));
    }

    /** Review #4: a REVERT plan blocked by SNAPSHOT_MISSING is refused at confirm; no op, alert or audit row. */
    @Test
    void aRevertPlanBlockedBySnapshotMissingIsNotConfirmable() {
        try (AwsOpsHarness h = drainOnlyReverted()) {
            h.jdbc.update("UPDATE spot_group SET snapshot_id = NULL WHERE id = ?", h.f.groupId);
            int ops = h.jdbc.queryForObject("SELECT count(*) FROM spot_operation", Integer.class);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> h.planAndConfirm(new PlanRequest(asgRef(),
                            OperationKind.REVERT, null, optionsFor(h, null))))
                    .isInstanceOfSatisfying(com.xammer.cloud.service.autospotting.core.AutoSpottingApiException.class,
                            e -> assertThat(e.getCode()).isEqualTo("SNAPSHOT_MISSING"));
            assertThat(h.jdbc.queryForObject("SELECT count(*) FROM spot_operation", Integer.class))
                    .as("no operation").isEqualTo(ops);
        }
    }

    /** Review #8: a link to a snapshot whose payload doesn't decode is "missing" for the blocker text too. */
    @Test
    void aLinkedButUnreadableOriginalGetsTheContactSupportText() {
        try (AwsOpsHarness h = drainOnlyReverted()) {
            UUID bad = UUID.randomUUID();
            h.jdbc.update("INSERT INTO spot_group_snapshot (id, group_id, provider, schema_version, exact, payload,"
                    + " payload_sha256, captured_at, captured_by_op) VALUES (?, ?, 'AWS', 1, true, '{\"junk\":true}'::jsonb,"
                    + " 'x', now(), ?)", bad, h.f.groupId, UUID.randomUUID());
            h.jdbc.update("UPDATE spot_group SET snapshot_id = ? WHERE id = ?", bad, h.f.groupId);
            assertThat(assertEnableBlocked(h)).contains("missing or can't be read").contains("Contact support")
                    .doesNotContain("Run Revert");
        }
    }

    /** Review #9: ADOPT is blocked too, and its confirm is refused. */
    @Test
    void anAdoptAfterADrainOnlyRevertIsBlockedAndNotConfirmable() {
        try (AwsOpsHarness h = drainOnlyReverted()) {
            PlanRequest adopt = new PlanRequest(asgRef(), OperationKind.ADOPT, new Profile("aggressive", null),
                    optionsFor(h, null));
            var plan = h.provider.createPlan(adopt, h.f.aws.view());
            assertThat(plan.eligibility().blockers()).extracting(b -> b.code())
                    .contains(AwsSpotProvider.CONFIG_STILL_ON_GROUP);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> h.planAndConfirm(adopt))
                    .isInstanceOf(com.xammer.cloud.service.autospotting.core.AutoSpottingApiException.class);
        }
    }

    /** Review #5: during the drain-only window status treats a customer-set policy as theirs, like planning. */
    @Test
    void afterADrainOnlyRevertStatusTreatsACustomerPolicyAsTheirsLikePlanning() {
        try (AwsOpsHarness h = drainOnlyReverted()) {
            assertThat(h.provider.getStatus(row(h), h.f.aws.view()).liveFingerprint()).as("100/200 is still XamOps'")
                    .isEqualTo(AwsFingerprint.of(AwsGroupFacts.from(FakeAws.REGION, h.f.group()), true));
            h.f.replaceGroup(h.f.group().toBuilder().instanceMaintenancePolicy(imp(90, 120)).build());
            AwsGroupFacts facts = AwsGroupFacts.from(FakeAws.REGION, h.f.group());
            assertThat(h.provider.getStatus(row(h), h.f.aws.view()).liveFingerprint())
                    .as("the customer's 90/120 is theirs in status").isEqualTo(AwsFingerprint.of(facts, false));
            assertThat(AwsSpotProvider.ownsPolicy(optionsFor(h, null), facts)).as("and in planning").isFalse();
        }
    }
}
