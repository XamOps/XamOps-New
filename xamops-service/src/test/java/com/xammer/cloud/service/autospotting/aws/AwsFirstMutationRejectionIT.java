package com.xammer.cloud.service.autospotting.aws;

import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.OperationKind;
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
 * Review round 2 #3 (design-core 9.4): when is an op "before the first mutation"? Both directions, through the real
 * OperationRunner and the AWS fake, for a PROGRESS op (ENABLE) and a SAFETY op (REVERT):
 * a definitive AWS 4xx rejection of the first mutating call on its only attempt is before mutation (fail back to
 * prior_state, no compensation); an applied mutation, a 5xx or a retried call is after mutation.
 */
@Tag("containers")
class AwsFirstMutationRejectionIT {

    private static AwsServiceException aws(int status, String code) {
        return (AwsServiceException) AwsServiceException.builder().statusCode(status)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode(code).build()).build();
    }

    /** Every update call: {@code answers} decides per call index (0-based) whether to throw; otherwise it applies. */
    private static void updates(AwsOpsHarness h, java.util.function.IntFunction<AwsServiceException> answers) {
        AtomicInteger n = new AtomicInteger();
        doAnswer(inv -> {
            UpdateAutoScalingGroupRequest r = inv.getArgument(0);
            AwsServiceException e = answers.apply(n.getAndIncrement());
            if (e != null) {
                throw e;
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

    private static String phase(AwsOpsHarness h, UUID op) {
        return h.jdbc.queryForObject("SELECT phase FROM spot_operation WHERE id = ?", String.class, op);
    }

    private static AwsOpsHarness enable() {
        return new AwsOpsHarness(FakeAws.asg("web-asg").build(), OperationKind.ENABLE, GroupState.PLANNED,
                GroupState.PLANNED, "conservative");
    }

    // ---- ENABLE (PROGRESS) ----

    @Test
    void anEnableWhoseFirstMutatingCallAwsRejectsWith400FailsBeforeMutationBackToPlanned() {
        try (AwsOpsHarness h = enable()) {
            updates(h, i -> aws(400, "ValidationError"));
            assertThat(h.runToEnd(h.f.opId)).isEqualTo("FAILED");
            assertThat(h.opError()).isEqualTo("AWS_VALIDATIONERROR");
            assertThat(phase(h, h.f.opId)).as("no compensation leg").isEqualTo("FORWARD");
            assertThat(h.groupState()).isEqualTo("PLANNED");
            assertThat(reasons(h)).containsExactly("ENABLE_STARTED", "FAILED_BEFORE_MUTATION");
            assertThat(h.f.updates).as("nothing was applied").isEmpty();
        }
    }

    @Test
    void anEnableWhoseMutationAppliedAndThenFailsTerminallyCompensates() {
        try (AwsOpsHarness h = enable()) {
            // The distribution update applies; right after it someone turns Capacity Rebalancing off, so the
            // verification after the rollout fails terminally (TARGET_MISMATCH): a failure after the mutation.
            AtomicInteger calls = new AtomicInteger();
            doAnswer(inv -> {
                UpdateAutoScalingGroupRequest r = inv.getArgument(0);
                h.f.log.add("update");
                h.f.updates.add(r);
                h.f.applyUpdate(r);
                if (calls.getAndIncrement() == 0) {
                    h.f.replaceGroup(h.f.group().toBuilder().capacityRebalance(false).build());
                }
                return UpdateAutoScalingGroupResponse.builder().build();
            }).when(h.f.write).updateAutoScalingGroup(any(UpdateAutoScalingGroupRequest.class));
            h.f.spotInstances.add("i-1");
            h.runToEnd(h.f.opId);
            assertThat(h.f.updates).as("the distribution was applied").isNotEmpty();
            assertThat(phase(h, h.f.opId)).as("the op compensated").isEqualTo("COMPENSATING");
            assertThat(reasons(h)).contains("ENABLE_STARTED", "ENABLE_COMPENSATING")
                    .doesNotContain("FAILED_BEFORE_MUTATION");
        }
    }

    @Test
    void anEnableWhoseFirstMutatingCallGetsA5xxThenA400IsAfterMutationAndCompensates() {
        try (AwsOpsHarness h = enable()) {
            // Call 0: 500 (outcome unknown, retried). Call 1: 400 on the retry. Later calls (compensation) apply.
            updates(h, i -> i == 0 ? aws(500, "InternalFailure") : i == 1 ? aws(400, "ValidationError") : null);
            h.runToEnd(h.f.opId);
            assertThat(reasons(h)).as("a retried call is after mutation").doesNotContain("FAILED_BEFORE_MUTATION")
                    .contains("ENABLE_COMPENSATING");
            assertThat(phase(h, h.f.opId)).isEqualTo("COMPENSATING");
            assertThat(h.groupState()).isNotEqualTo("PLANNED");
        }
    }

    // ---- REVERT (SAFETY) ----

    private static AutoScalingGroup converted() {
        List<software.amazon.awssdk.services.autoscaling.model.Instance> list = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            list.add(FakeAws.instance("i-" + i, false));
        }
        return FakeAws.asg("web-asg").launchTemplate((LaunchTemplateSpecification) null)
                .capacityRebalance(true).instances(list)
                .tags(FakeAws.tag(AwsGroupFacts.MARKER_TAG, "marker"))
                .instanceMaintenancePolicy(InstanceMaintenancePolicy.builder().minHealthyPercentage(100)
                        .maxHealthyPercentage(200).build())
                .mixedInstancesPolicy(MixedInstancesPolicy.builder().launchTemplate(LaunchTemplate.builder()
                        .launchTemplateSpecification(LaunchTemplateSpecification.builder().launchTemplateId("lt-0abc")
                                .version("$Default").build())
                        .overrides(LaunchTemplateOverrides.builder().instanceType("m5.large").build(),
                                LaunchTemplateOverrides.builder().instanceType("m5a.large").build()).build())
                        .instancesDistribution(InstancesDistribution.builder().onDemandAllocationStrategy("prioritized")
                                .onDemandBaseCapacity(1).onDemandPercentageAboveBaseCapacity(50)
                                .spotAllocationStrategy("price-capacity-optimized").build()).build())
                .build();
    }

    private static final String DESIRED = "{\"shape\":\"MIXED_INSTANCES_POLICY\",\"launchTemplate\":{\"id\":\"lt-0abc\","
            + "\"version\":\"$Default\"},\"overrides\":[\"m5.large\",\"m5a.large\"],\"instancesDistribution\":"
            + "{\"onDemandAllocationStrategy\":\"prioritized\",\"onDemandBaseCapacity\":1,"
            + "\"onDemandPercentageAboveBaseCapacity\":50,\"spotAllocationStrategy\":\"price-capacity-optimized\"},"
            + "\"capacityRebalance\":true,\"instanceMaintenancePolicy\":{\"owned\":true,\"min\":100,\"max\":200}}";

    @Test
    void aRevertWhoseFirstMutatingCallAwsRejectsWith400FailsBeforeMutationBackToActive() {
        try (AwsOpsHarness h = new AwsOpsHarness(converted(), FakeAws.asg("web-asg").build(), GroupState.ACTIVE, DESIRED)) {
            updates(h, i -> aws(400, "ValidationError"));
            assertThat(h.runToEnd(h.f.opId)).isEqualTo("FAILED");
            assertThat(h.groupState()).as("nothing changed: back to prior_state").isEqualTo("ACTIVE");
            assertThat(reasons(h)).contains("FAILED_BEFORE_MUTATION").doesNotContain("REVERT_FAILED");
            assertThat(h.f.updates).isEmpty();
        }
    }

    @Test
    void aRevertWhoseFirstMutatingCallGetsA5xxThenA400IsAfterMutationAndEndsFailed() {
        try (AwsOpsHarness h = new AwsOpsHarness(converted(), FakeAws.asg("web-asg").build(), GroupState.ACTIVE, DESIRED)) {
            updates(h, i -> i == 0 ? aws(500, "InternalFailure") : aws(400, "ValidationError"));
            assertThat(h.runToEnd(h.f.opId)).isEqualTo("FAILED");
            assertThat(h.groupState()).as("the outcome of the 5xx is unknown: after mutation").isEqualTo("FAILED");
            assertThat(reasons(h)).contains("REVERT_FAILED").doesNotContain("FAILED_BEFORE_MUTATION");
        }
    }
}
