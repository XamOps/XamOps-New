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
                new Profile("aggressive", null), new PlanOptions(allowBurstable, desired));
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
            UUID op = h.planAndConfirm(enable);
            assertThat(h.runToEnd(op)).as("enable error " + h.jdbc.queryForList(
                    "SELECT error_code FROM spot_operation WHERE id = ?", String.class, op)).isEqualTo("SUCCEEDED");
            assertThat(h.groupState()).isEqualTo("ACTIVE");
            InstanceMaintenancePolicy after = h.f.group().instanceMaintenancePolicy();
            assertThat(after).as("live policy after the re-enable").isNotNull();
            assertThat(after.minHealthyPercentage()).isEqualTo(expected.minHealthyPercentage());
            assertThat(after.maxHealthyPercentage()).isEqualTo(expected.maxHealthyPercentage());
            assertThat(h.f.updates.stream().filter(r -> r.instanceMaintenancePolicy() != null
                            && r.instanceMaintenancePolicy().minHealthyPercentage() != null
                            && r.instanceMaintenancePolicy().minHealthyPercentage() == 100
                            && customer != null))
                    .as("no update after the revert wrote 100/200 over the customer's policy").isEmpty();
        }
    }
    /** D-23b-01 as found live: enable, revert, the customer sets 90/120, a confirmed re-enable keeps it. */
    @Test
    void aReEnableAfterARevertKeepsTheCustomersMaintenancePolicyThroughConfirm() {
        reEnableAfterRevert(imp(90, 120), imp(90, 120));
    }
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
}
