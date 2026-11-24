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
}
