package com.xammer.cloud.service.autospotting.aws;

import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.OperationKind;
import com.xammer.cloud.domain.autospotting.SpotGroup;
import com.xammer.cloud.repository.autospotting.ReconcilerStore;
import com.xammer.cloud.repository.autospotting.SpotGroupRepository;
import com.xammer.cloud.repository.autospotting.SpotOperationRepository;
import com.xammer.cloud.service.autospotting.core.ops.PreemptionService;
import com.xammer.cloud.service.autospotting.core.reconcile.HealthPass;
import com.xammer.cloud.service.autospotting.obs.AutoSpottingMetrics;
import com.xammer.cloud.service.autospotting.spi.GroupStatus;
import com.xammer.cloud.service.autospotting.spi.ReconcileAction;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.autoscaling.model.Activity;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;
import software.amazon.awssdk.services.autoscaling.model.DescribeScalingActivitiesRequest;
import software.amazon.awssdk.services.autoscaling.model.DescribeScalingActivitiesResponse;

import java.time.Duration;
import java.util.List;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * The shortage rule on real PostgreSQL (design-aws.md §2.5 tests, plan-phase-18.md task 18.2,
 * AC-AWS-05, AC-AWS-08 mechanics): a scale-out during ENABLE whose new Spot launches fail compensates
 * after the 10 minute window; the same under switch (d) through {@code compensate_requested}; under the
 * freeze nothing runs and an alert fires. Stubbed SDK; the ENABLE compensation runs the real REVERT
 * program (drain Spot, wait, restore the snapshot).
 */
@Tag("containers")
class AwsRolloutShortageIT {

    /** A group that scaled out to 5 while only 4 instances exist, as when new Spot launches fail. */
    private static AutoScalingGroup scaledOut() {
        return FakeAws.asg("web-asg").desiredCapacity(5).maxSize(8).build();
    }

    private static AutoScalingGroup fiveInstances(AutoScalingGroup g) {
        return g.toBuilder().instances(FakeAws.instance("i-1", false), FakeAws.instance("i-2", false),
                FakeAws.instance("i-3", false), FakeAws.instance("i-4", false), FakeAws.instance("i-5", false)).build();
    }

    /** Spot-capacity launch failures: one a while ago (the streak start), one a minute ago. */
    private static void spotFailures(AwsOpsHarness h, Duration streakAge) {
        doAnswer(inv -> {
            java.time.Instant now = h.f.clock.instant();
            Activity recent = Activity.builder().activityId("fail-2").statusCode("Failed")
                    .description("Launching a new EC2 instance").startTime(now.minusSeconds(60))
                    .statusMessage("There is no Spot capacity available that matches your request.").build();
            Activity first = Activity.builder().activityId("fail-1").statusCode("Failed")
                    .description("Launching a new EC2 instance").startTime(now.minus(streakAge))
                    .statusMessage("There is no Spot capacity available that matches your request.").build();
            return DescribeScalingActivitiesResponse.builder().activities(recent, first).build();
        }).when(h.f.aws.asg).describeScalingActivities(any(DescribeScalingActivitiesRequest.class));
    }

    private static AwsOpsHarness enableHarness() {
        AwsOpsHarness h = new AwsOpsHarness(scaledOut(), OperationKind.ENABLE, GroupState.APPLYING,
                GroupState.UNMANAGED, "conservative");
        spotFailures(h, Duration.ofMinutes(1));
        return h;
    }

    /** One poll later: claim the parked op and run it to its next boundary. */
    private static void pollAfter(AwsOpsHarness h, Duration wait) {
        h.f.clock.advance(wait);
        h.claimAndRun();
    }

    /** The ENABLE compensates through the real REVERT program: the group enters REVERTING and Spot is drained first. */
    private void assertCompensatingThroughTheRevertProgram(AwsOpsHarness h) {
        assertThat(h.opPhase()).isEqualTo("COMPENSATING");
        assertThat(h.revert.calls).as("the REVERT program was asked for the snapshot program").isGreaterThan(0);
        assertThat(h.groupState()).isEqualTo("REVERTING");
        assertThat(h.opStatus()).as("it waits for the drain, it does not fail").isEqualTo("WAITING");
        assertThat(h.f.updates).as("the conversion, then the drain").hasSize(2);
        assertThat(h.f.updates.get(1).mixedInstancesPolicy().instancesDistribution()
                .onDemandPercentageAboveBaseCapacity()).isEqualTo(100);
        assertThat(h.count("SELECT count(*) FROM spot_group_snapshot WHERE captured_by_op = ?", h.f.opId))
                .as("the snapshot to restore exists").isEqualTo(1);
    }

    @Test
    void aScaleOutDuringEnableCompensatesAfterTheTenMinuteWindow() {
        try (AwsOpsHarness h = enableHarness()) {
            h.runner.runClaimed(h.lease, h.f.opId);
            assertThat(h.opStatus()).isEqualTo("WAITING");
            assertThat(h.opWaitReason()).isEqualTo("CLOUD_PROGRESS");

            pollAfter(h, Duration.ofMinutes(9));
            assertThat(h.opStatus()).as("still inside the window").isEqualTo("WAITING");
            assertThat(h.opPhase()).isEqualTo("FORWARD");

            pollAfter(h, Duration.ofMinutes(1));
            assertCompensatingThroughTheRevertProgram(h);
            // Capacity returns as On-Demand: the restore unwraps the policy and the group ends REVERTED.
            h.f.replaceGroup(fiveInstances(h.f.group()));
            h.claimAndRun();
            assertThat(h.opStatus()).isEqualTo("SUCCEEDED");
            assertThat(h.groupState()).isEqualTo("REVERTED");
            assertThat(h.f.group().mixedInstancesPolicy()).isNull();
            assertThat(h.f.group().capacityRebalance()).isFalse();
        }
    }

    @Test
    void aConfigApplyShortageRestoresThePriorConfigurationAndReturnsToActive() {
        try (AwsOpsHarness h = new AwsOpsHarness(scaledOut(), OperationKind.CONFIG_APPLY, GroupState.APPLYING,
                GroupState.ACTIVE, "balanced")) {
            spotFailures(h, Duration.ofMinutes(1));
            h.runner.runClaimed(h.lease, h.f.opId);
            pollAfter(h, Duration.ofMinutes(10));

            // Compensating toward PRIOR_CONFIG: the update restoring the saved launch-template shape is sent,
            // then the leg waits for capacity (the lenient rule), never failing into another compensation.
            assertThat(h.opPhase()).isEqualTo("COMPENSATING");
            assertThat(h.f.updates).hasSize(2);
            assertThat(h.f.group().mixedInstancesPolicy()).isNull();
            assertThat(h.opStatus()).isEqualTo("WAITING");

            // On-Demand capacity comes back; the leg verifies and the group returns to its prior state.
            h.f.replaceGroup(fiveInstances(h.f.group()));
            h.claimAndRun();

            assertThat(h.opStatus()).isEqualTo("SUCCEEDED");
            assertThat(h.groupState()).isEqualTo("ACTIVE");
            assertThat(h.jdbc.queryForObject("SELECT desired_config IS NULL FROM spot_group WHERE id = ?",
                    Boolean.class, h.f.groupId)).as("compensation never writes desired_config").isTrue();
            assertThat(h.f.group().capacityRebalance()).isFalse();
        }
    }

    private HealthPass healthPass(AwsOpsHarness h) {
        return new HealthPass(h.ctx.getBean(SpotOperationRepository.class), h.ctx.getBean(PreemptionService.class),
                new ReconcilerStore(h.ctx.getBean(DataSource.class)), h.ctx.getBean(AutoSpottingMetrics.class),
                h.ctx.getBean(com.xammer.cloud.service.autospotting.core.reconcile.FallbackEnqueuer.class));
    }

    /** The reconciler's sweep for the group: getStatus, reconcile, then the read-only health pass. */
    private List<ReconcileAction> sweep(AwsOpsHarness h) {
        SpotGroup group = h.ctx.getBean(SpotGroupRepository.class).findById(h.f.groupId).orElseThrow();
        GroupStatus status = h.provider.getStatus(group, h.f.aws.view());
        List<ReconcileAction> actions = h.provider.reconcile(group, status);
        healthPass(h).run(group, status, actions);
        return actions;
    }

    private double alerts(AwsOpsHarness h) {
        var counter = h.ctx.getBean(SimpleMeterRegistry.class).find("autospotting.reconcile.alert").counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void theSameShortageUnderSwitchDCompensatesThroughCompensateRequested() {
        try (AwsOpsHarness h = enableHarness()) {
            spotFailures(h, Duration.ofMinutes(11));
            h.runner.runClaimed(h.lease, h.f.opId);
            h.engageSwitch("ACCOUNT:7");
            pollAfter(h, Duration.ofMinutes(11));
            assertThat(h.opStatus()).isEqualTo("WAITING");
            assertThat(h.opWaitReason()).as("a PROGRESS op pauses under switch (d)").isEqualTo("SWITCH");
            assertThat(h.opPhase()).isEqualTo("FORWARD");

            List<ReconcileAction> actions = sweep(h);

            assertThat(actions).contains(ReconcileAction.REQUEST_COMPENSATION, ReconcileAction.ALERT);
            assertThat(h.jdbc.queryForObject("SELECT compensate_requested FROM spot_operation WHERE id = ?",
                    Boolean.class, h.f.opId)).isTrue();
            h.claimAndRun(); // compensation is SAFETY: it runs under switch (d)
            assertCompensatingThroughTheRevertProgram(h);
        }
    }

    @Test
    void underTheFreezeNothingRunsAndAnAlertFires() {
        try (AwsOpsHarness h = enableHarness()) {
            spotFailures(h, Duration.ofMinutes(11));
            h.runner.runClaimed(h.lease, h.f.opId);
            h.engageSwitch("FREEZE");
            h.f.clock.advance(Duration.ofMinutes(11));
            double before = alerts(h);

            List<ReconcileAction> actions = sweep(h);
            h.claimAndRun();

            assertThat(actions).contains(ReconcileAction.REQUEST_COMPENSATION, ReconcileAction.ALERT);
            assertThat(alerts(h)).as("an alert fired").isGreaterThan(before);
            assertThat(h.opStatus()).isEqualTo("WAITING");
            assertThat(h.opWaitReason()).isEqualTo("SWITCH");
            assertThat(h.opError()).as("no restore step ran under the freeze").isNull();
            assertThat(h.f.updates).as("no further AWS write under the freeze").hasSize(1);
            assertThat(h.count("SELECT count(*) FROM spot_operation_step WHERE operation_id = ? AND seq >= 10000",
                    h.f.opId)).as("no compensation step started").isZero();
        }
    }
}
