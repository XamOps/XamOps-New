package com.xammer.cloud.service.autospotting.aws;

import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.OperationKind;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;
import software.amazon.awssdk.services.autoscaling.model.UpdateAutoScalingGroupRequest;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ENABLE then REVERT of one group on real PostgreSQL over a stubbed SDK (G5 validation). The revert starts from the
 * group the enable left behind and from the original shape the enable captured. The group must come back to its
 * original configuration, no request may write a capacity field, and the in-service instance count must never be
 * below the desired capacity at any observed step. The step sequence is printed with the prefix {@code G5SEQ}.
 */
@Tag("containers")
class AwsEnableRevertRoundTripIT {
    private static final List<String> SEQUENCE = new ArrayList<>();

    private static void observe(String label, AutoScalingGroup g) {
        int inService = g.instances().size();
        SEQUENCE.add(label + " | desired=" + g.desiredCapacity() + " min=" + g.minSize() + " max=" + g.maxSize()
                + " instances=" + inService + " mixedPolicy=" + (g.mixedInstancesPolicy() != null)
                + " capacityRebalance=" + g.capacityRebalance());
        assertThat(inService).as(label).isGreaterThanOrEqualTo(g.desiredCapacity());
    }

    private static void assertNoCapacityWrite(List<UpdateAutoScalingGroupRequest> updates) {
        for (UpdateAutoScalingGroupRequest r : updates) {
            assertThat(r.desiredCapacity()).isNull();
            assertThat(r.minSize()).isNull();
            assertThat(r.maxSize()).isNull();
        }
    }

    @Test
    void enableThenRevertReturnsTheGroupToItsOriginalConfigurationWithNoCapacityBelowTarget() {
        AutoScalingGroup original = FakeAws.asg("web-asg").build();
        String desired;
        AutoScalingGroup enabled;
        try (AwsOpsHarness h = new AwsOpsHarness(original, OperationKind.ENABLE, GroupState.APPLYING,
                GroupState.UNMANAGED, "conservative")) {
            h.f.spotInstances.add("i-1");
            observe("ENABLE start", h.f.group());
            h.runner.runClaimed(h.lease, h.f.opId);
            observe("ENABLE end", h.f.group());
            assertThat(h.opStatus()).isEqualTo("SUCCEEDED");
            assertThat(h.groupState()).isEqualTo("ACTIVE");
            SEQUENCE.add("ENABLE steps: " + h.jdbc.queryForList("SELECT seq || ':' || name || ':' || status FROM"
                    + " spot_operation_step WHERE operation_id = ? ORDER BY seq", String.class, h.f.opId));
            SEQUENCE.add("ENABLE sdk calls: " + h.f.log);
            assertNoCapacityWrite(h.f.updates);
            desired = h.jdbc.queryForObject("SELECT desired_config::text FROM spot_group WHERE id = ?", String.class,
                    h.f.groupId);
            enabled = h.f.group();
        }
        assertThat(enabled.mixedInstancesPolicy()).isNotNull();
        try (AwsOpsHarness h = new AwsOpsHarness(enabled, original, GroupState.ACTIVE, desired)) {
            h.f.spotInstances.add("i-1");
            observe("REVERT start", h.f.group());
            h.runner.runClaimed(h.lease, h.f.opId);
            observe("REVERT after drain (Spot still running)", h.f.group());
            assertThat(h.groupState()).isEqualTo("REVERTING");
            h.f.spotInstances.clear();
            h.f.clock.advance(Duration.ofMinutes(1));
            h.claimAndRun();
            observe("REVERT end", h.f.group());
            assertThat(h.opStatus()).isEqualTo("SUCCEEDED");
            assertThat(h.groupState()).isEqualTo("REVERTED");
            SEQUENCE.add("REVERT steps: " + h.jdbc.queryForList("SELECT seq || ':' || name || ':' || status FROM"
                    + " spot_operation_step WHERE operation_id = ? ORDER BY seq", String.class, h.f.opId));
            SEQUENCE.add("REVERT sdk calls: " + h.f.log);
            assertNoCapacityWrite(h.f.updates);
            AutoScalingGroup back = h.f.group();
            assertThat(back.mixedInstancesPolicy()).isEqualTo(original.mixedInstancesPolicy());
            assertThat(back.launchTemplate()).isEqualTo(original.launchTemplate());
            // An unset flag (null) and false mean the same to AWS; the revert writes the explicit false.
            assertThat(Boolean.TRUE.equals(back.capacityRebalance())).isEqualTo(Boolean.TRUE.equals(original.capacityRebalance()));
            assertThat(back.desiredCapacity()).isEqualTo(original.desiredCapacity());
            assertThat(back.minSize()).isEqualTo(original.minSize());
            assertThat(back.maxSize()).isEqualTo(original.maxSize());
            assertThat(back.instances()).isEqualTo(original.instances());
            assertThat(back.tags()).isEqualTo(original.tags());
            // Tags were compared above as lists; the SDK keeps an unset list and an empty list apart.
            assertThat(back.toBuilder().capacityRebalance(original.capacityRebalance()).tags(original.tags()).build())
                    .as("whole group equals the original").isEqualTo(original);
        }
        SEQUENCE.forEach(s -> System.out.println("G5SEQ " + s));
    }
}
