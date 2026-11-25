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
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;
import software.amazon.awssdk.services.autoscaling.model.InstanceMaintenancePolicy;
import software.amazon.awssdk.services.autoscaling.model.InstancesDistribution;
import software.amazon.awssdk.services.autoscaling.model.LaunchTemplate;
import software.amazon.awssdk.services.autoscaling.model.LaunchTemplateOverrides;
import software.amazon.awssdk.services.autoscaling.model.LaunchTemplateSpecification;
import software.amazon.awssdk.services.autoscaling.model.MixedInstancesPolicy;
import software.amazon.awssdk.services.autoscaling.model.UpdateAutoScalingGroupRequest;
import software.amazon.awssdk.services.autoscaling.model.UpdateAutoScalingGroupResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * Review round 3 (design-core 9.4, the conservative rule): once the first mutating step's intent row exists, any
 * terminal failure, including a 4xx the cloud answered with no effect, is after mutation and compensates. Pinned
 * through the real OperationRunner and the AWS fake: (a) ENABLE, (b) REVERT, (c) the re-enable after a drain-only
 * revert (configOnGroup), which must end revertable.
 */
@Tag("containers")
class AwsConservativeMutationRuleIT {

    private static AwsServiceException rejected() {
        return (AwsServiceException) AwsServiceException.builder().statusCode(400)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("ValidationError").build()).build();
    }

    /** The first {@code rejections} update calls are refused with a 400 (no effect); the rest apply. */
    private static void rejectFirstUpdates(AwsOpsHarness h, int rejections) {
        AtomicInteger n = new AtomicInteger();
        doAnswer(inv -> {
            UpdateAutoScalingGroupRequest r = inv.getArgument(0);
            if (n.getAndIncrement() < rejections) {
                throw rejected();
            }
            h.f.log.add("update");
            h.f.updates.add(r);
            h.f.applyUpdate(r);
            return UpdateAutoScalingGroupResponse.builder().build();
        }).when(h.f.write).updateAutoScalingGroup(any(UpdateAutoScalingGroupRequest.class));
    }

    private static List<String> reasons(AwsOpsHarness h) {
        return h.jdbc.queryForList("SELECT reason_code FROM spot_group_transition WHERE group_id = ? ORDER BY id",
                String.class, h.f.groupId);
    }

    private static String col(AwsOpsHarness h, String column, UUID op) {
        return h.jdbc.queryForObject("SELECT " + column + "::text FROM spot_operation WHERE id = ?", String.class, op);
    }

    /** (a) A rejected first distribution update: the ENABLE compensates to its snapshot and ends REVERTED, original intact. */
    @Test
    void anEnableWhoseFirstMutatingCallIsRejectedCompensatesToItsSnapshotAndEndsReverted() {
        AutoScalingGroup original = FakeAws.asg("web-asg").build();
        try (AwsOpsHarness h = new AwsOpsHarness(original, OperationKind.ENABLE, GroupState.PLANNED,
                GroupState.PLANNED, "conservative")) {
            rejectFirstUpdates(h, 1);
            assertThat(h.runToEnd(h.f.opId)).as("op error " + h.opError()).isEqualTo("SUCCEEDED");
            assertThat(col(h, "phase", h.f.opId)).as("the op compensated").isEqualTo("COMPENSATING");
            assertThat(col(h, "first_mutation_seq", h.f.opId)).as("intent recorded before the call").isNotNull();
            assertThat(h.groupState()).as("the compensation target").isEqualTo("REVERTED");
            assertThat(reasons(h)).contains("ENABLE_STARTED", "ENABLE_COMPENSATING")
                    .doesNotContain("FAILED_BEFORE_MUTATION");
            AutoScalingGroup now = h.f.group();
            assertThat(now.mixedInstancesPolicy()).as("the original shape").isNull();
            assertThat(now.launchTemplate()).isEqualTo(original.launchTemplate());
            assertThat(now.instanceMaintenancePolicy()).as("no policy, as before").isNull();
        }
    }

    private static AutoScalingGroup converted(InstanceMaintenancePolicy policy, List<String> types) {
        List<software.amazon.awssdk.services.autoscaling.model.Instance> list = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            list.add(FakeAws.instance("i-" + i, false));
        }
        return FakeAws.asg("web-asg").launchTemplate((LaunchTemplateSpecification) null)
                .capacityRebalance(true).instances(list)
                .tags(FakeAws.tag(AwsGroupFacts.MARKER_TAG, "marker"))
                .instanceMaintenancePolicy(policy)
                .mixedInstancesPolicy(MixedInstancesPolicy.builder().launchTemplate(LaunchTemplate.builder()
                        .launchTemplateSpecification(LaunchTemplateSpecification.builder().launchTemplateId("lt-0abc")
                                .version("$Default").build())
                        .overrides(types.stream().map(t -> LaunchTemplateOverrides.builder().instanceType(t).build())
                                .toList()).build())
                        .instancesDistribution(InstancesDistribution.builder().onDemandAllocationStrategy("prioritized")
                                .onDemandBaseCapacity(1).onDemandPercentageAboveBaseCapacity(50)
                                .spotAllocationStrategy("price-capacity-optimized").build()).build())
                .build();
    }

    private static String desired(List<String> types, String extra) {
        return "{\"shape\":\"MIXED_INSTANCES_POLICY\",\"launchTemplate\":{\"id\":\"lt-0abc\",\"version\":\"$Default\"},"
                + "\"overrides\":" + types.stream().map(t -> "\"" + t + "\"").toList()
                + ",\"instancesDistribution\":{\"onDemandAllocationStrategy\":\"prioritized\",\"onDemandBaseCapacity\":1,"
                + "\"onDemandPercentageAboveBaseCapacity\":50,\"spotAllocationStrategy\":\"price-capacity-optimized\"},"
                + "\"capacityRebalance\":true,\"instanceMaintenancePolicy\":{\"owned\":true,\"min\":100,\"max\":200}"
                + extra + "}";
    }

    private static InstanceMaintenancePolicy owned() {
        return InstanceMaintenancePolicy.builder().minHealthyPercentage(100).maxHealthyPercentage(200).build();
    }

    /** (b) A rejected first revert write: the REVERT ends FAILED (a SAFETY op after mutation) and is offered again. */
    @Test
    void aRevertWhoseFirstMutatingCallIsRejectedEndsFailedAndARetryRevertRestoresTheOriginal() {
        List<String> types = List.of("m5.large", "m5a.large");
        try (AwsOpsHarness h = new AwsOpsHarness(converted(owned(), types), FakeAws.asg("web-asg").build(),
                GroupState.ACTIVE, desired(types, ""))) {
            rejectFirstUpdates(h, 1);
            assertThat(h.runToEnd(h.f.opId)).isEqualTo("FAILED");
            assertThat(col(h, "first_mutation_seq", h.f.opId)).isNotNull();
            assertThat(h.groupState()).as("after mutation: FAILED, not back to ACTIVE").isEqualTo("FAILED");
            assertThat(reasons(h)).contains("REVERT_FAILED").doesNotContain("FAILED_BEFORE_MUTATION");
            assertThat(h.f.updates).as("the rejected call changed nothing").isEmpty();
            assertThat(AwsMaintenancePolicy.owns(PlanOptions.ownershipConfigJson(row(h))))
                    .as("nothing was restored, so the claim stays").isTrue();
            // FAILED --> REVERTING: the revert is offered again and restores the original.
            // Round 4 #5: the retry is planned with the row's own options, as PlanService builds them.
            UUID retry = h.planAndConfirm(new PlanRequest(ref(), OperationKind.REVERT, null,
                    PlanOptions.forGroup(null, row(h))));
            assertThat(h.runToEnd(retry)).as("retry error " + h.opError()).isEqualTo("SUCCEEDED");
            assertThat(h.groupState()).isEqualTo("REVERTED");
            assertThat(h.f.group().mixedInstancesPolicy()).isNull();
            assertThat(h.f.group().instanceMaintenancePolicy()).isNull();
        }
    }

    private static GroupRef ref() {
        return new GroupRef(Provider.AWS, 7L, FakeAws.REGION + "/web-asg", null);
    }

    private static com.xammer.cloud.domain.autospotting.SpotGroup row(AwsOpsHarness h) {
        return h.ctx.getBean(com.xammer.cloud.repository.autospotting.SpotGroupRepository.class)
                .findById(h.f.groupId).orElseThrow();
    }

    /**
     * (c) After a drain-only revert (configOnGroup), the re-enable's first mutating call is rejected. The ENABLE
     * compensates to the kept original snapshot: XamOps' configuration leaves the group, the markers are released,
     * and the group ends REVERTED, so it is never stranded (a new enable plan or GR-02 revert is possible).
     */
    @Test
    void aReEnableAfterADrainOnlyRevertWhoseFirstCallIsRejectedEndsRevertedOnTheTrueOriginal() {
        List<String> types = List.of("m5.large", "m5a.large");
        AutoScalingGroup original = FakeAws.asg("web-asg").build();
        try (AwsOpsHarness h = new AwsOpsHarness(converted(owned(), types), original, GroupState.ACTIVE,
                desired(types, ""), true, fx -> org.mockito.Mockito.doThrow(AwsServiceException.builder()
                        .statusCode(403).awsErrorDetails(AwsErrorDetails.builder().errorCode("AccessDenied").build())
                        .build()).when(fx.aws.iam).simulatePrincipalPolicy(any(
                        software.amazon.awssdk.services.iam.model.SimulatePrincipalPolicyRequest.class)))) {
            assertThat(h.f.json(h.plan.targetJson()).path("mode").asText()).isEqualTo("DRAIN_ONLY");
            h.runner.runClaimed(h.lease, h.f.opId);
            assertThat(h.runToEnd(h.f.opId)).as("drain-only error " + h.opError()).isEqualTo("SUCCEEDED");
            assertThat(h.groupState()).isEqualTo("REVERTED");
            assertThat(PlanOptions.forGroup(null, row(h)).keepsOriginal()).as("configOnGroup with the original").isTrue();
            org.mockito.Mockito.reset(h.f.aws.iam);
            doAnswer(inv -> {
                software.amazon.awssdk.services.iam.model.SimulatePrincipalPolicyRequest r = inv.getArgument(0);
                return software.amazon.awssdk.services.iam.model.SimulatePrincipalPolicyResponse.builder()
                        .evaluationResults(r.actionNames().stream().map(a -> software.amazon.awssdk.services.iam.model
                                .EvaluationResult.builder().evalActionName(a).evalDecision("allowed").build()).toList())
                        .build();
            }).when(h.f.aws.iam).simulatePrincipalPolicy(any(
                    software.amazon.awssdk.services.iam.model.SimulatePrincipalPolicyRequest.class));
            AwsConfirmedOpsIT.toPlanned(h);
            rejectFirstUpdates(h, 1);
            UUID enable = h.planAndConfirm(new PlanRequest(ref(), OperationKind.ENABLE,
                    new Profile("aggressive", null), PlanOptions.forGroup(null, row(h))));
            assertThat(h.runToEnd(enable)).as("re-enable error " + h.jdbc.queryForList(
                    "SELECT error_code FROM spot_operation WHERE id = ?", String.class, enable)).isEqualTo("SUCCEEDED");
            assertThat(col(h, "phase", enable)).as("compensated").isEqualTo("COMPENSATING");
            assertThat(h.groupState()).as("revertable end state, not stranded").isEqualTo("REVERTED");
            assertThat(h.f.group().mixedInstancesPolicy()).as("the true original is restored").isNull();
            assertThat(h.f.group().instanceMaintenancePolicy()).as("100/200 cleared").isNull();
            assertThat(PlanOptions.forGroup(null, row(h)).underManagement()).as("claims released").isFalse();
            assertThat(h.jdbc.queryForObject("SELECT count(*) FROM spot_group_snapshot WHERE group_id = ?",
                    Integer.class, h.f.groupId)).as("the original snapshot was kept").isEqualTo(1);
        }
    }

    /**
     * Round 4 #4: the conservative rule on a group with the customer's own settings. A CONFIG_APPLY whose first
     * update is rejected compensates to its prior configuration; the customer's 90/120 policy and the type they
     * added must survive the compensation.
     */
    @Test
    void aRejectedFirstCallOnAGroupWithCustomerSettingsCompensatesWithoutOverwritingThem() {
        List<String> types = List.of("m5.large", "m5a.large", "c5.large"); // c5.large: added by the customer
        InstanceMaintenancePolicy customer = InstanceMaintenancePolicy.builder().minHealthyPercentage(90)
                .maxHealthyPercentage(120).build();
        String stored = "{\"shape\":\"MIXED_INSTANCES_POLICY\",\"launchTemplate\":{\"id\":\"lt-0abc\","
                + "\"version\":\"$Default\"},\"overrides\":[\"m5.large\",\"m5a.large\"],\"instancesDistribution\":"
                + "{\"onDemandAllocationStrategy\":\"prioritized\",\"onDemandBaseCapacity\":1,"
                + "\"onDemandPercentageAboveBaseCapacity\":50,\"spotAllocationStrategy\":\"price-capacity-optimized\"},"
                + "\"capacityRebalance\":true}";
        try (AwsOpsHarness h = new AwsOpsHarness(converted(customer, types), GroupState.ACTIVE, "MANAGED", stored,
                null, null, null)) {
            h.linkSnapshot(FakeAws.asg("web-asg").build());
            UUID op = h.planAndConfirm(new PlanRequest(ref(), OperationKind.CONFIG_APPLY,
                    new Profile("aggressive", null), PlanOptions.forGroup(null, row(h))));
            rejectFirstUpdates(h, 1);
            assertThat(h.runToEnd(op)).as("op error " + h.jdbc.queryForList(
                    "SELECT error_code FROM spot_operation WHERE id = ?", String.class, op)).isEqualTo("SUCCEEDED");
            assertThat(col(h, "phase", op)).as("compensated (conservative rule)").isEqualTo("COMPENSATING");
            assertThat(h.groupState()).isEqualTo("ACTIVE");
            assertThat(h.f.updates).as("no update carried a maintenance policy")
                    .allSatisfy(r -> assertThat(r.instanceMaintenancePolicy()).isNull());
            assertThat(h.f.group().instanceMaintenancePolicy()).as("the customer's policy").isEqualTo(customer);
            List<String> live = new ArrayList<>();
            h.f.group().mixedInstancesPolicy().launchTemplate().overrides().forEach(o -> live.add(o.instanceType()));
            assertThat(live).as("the customer's added type").contains("c5.large");
        }
    }
}
