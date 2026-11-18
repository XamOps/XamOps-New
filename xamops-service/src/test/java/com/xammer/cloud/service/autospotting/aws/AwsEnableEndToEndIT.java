package com.xammer.cloud.service.autospotting.aws;

import com.fasterxml.jackson.databind.JsonNode;
import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.OperationKind;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.autoscaling.model.Activity;
import software.amazon.awssdk.services.autoscaling.model.DescribeScalingActivitiesRequest;
import software.amazon.awssdk.services.autoscaling.model.DescribeScalingActivitiesResponse;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;

/**
 * plan -> confirm -> APPLYING -> ACTIVE on real PostgreSQL with the real runner and the real AWS
 * provider over a stubbed SDK (plan-phase-18.md task 18.3; AC-AWS-02, AC-AWS-04, AC-HLT-06). The
 * mutation switches are on in this test context only; no AWS call is made, and the write client has no
 * instance-refresh method to call.
 */
@Tag("containers")
class AwsEnableEndToEndIT {

    private AwsOpsHarness harness() {
        return new AwsOpsHarness(FakeAws.asg("web-asg").build(), OperationKind.ENABLE, GroupState.APPLYING,
                GroupState.UNMANAGED, "conservative");
    }

    @Test
    void enableRunsToActiveWithTransitionsAuditAndDesiredConfigWritten() throws IOException {
        try (AwsOpsHarness h = harness()) {
            h.f.spotInstances.add("i-1"); // the profile expects one Spot instance at desired 4

            h.runner.runClaimed(h.lease, h.f.opId);

            assertThat(h.opStatus()).isEqualTo("SUCCEEDED");
            assertThat(h.groupState()).isEqualTo("ACTIVE");
            // Seven recorded steps, all DONE, in program order; the first mutation is UPDATE_DISTRIBUTION (seq 3).
            List<Map<String, Object>> steps = h.jdbc.queryForList(
                    "SELECT seq, name, status FROM spot_operation_step WHERE operation_id = ? ORDER BY seq", h.f.opId);
            assertThat(steps).extracting(s -> s.get("name")).containsExactly("VERIFY_PRECONDITIONS",
                    "CHECK_PERMISSIONS", "PERSIST_SNAPSHOT", "UPDATE_DISTRIBUTION", "WAIT_ROLLOUT", "VERIFY_TARGET",
                    "WRITE_MARKER");
            assertThat(steps).extracting(s -> s.get("status")).containsOnly("DONE");
            assertThat(h.jdbc.queryForObject("SELECT first_mutation_seq FROM spot_operation WHERE id = ?",
                    Integer.class, h.f.opId)).isEqualTo(3);

            // The transition and the audit rows.
            assertThat(h.count("SELECT count(*) FROM spot_group_transition WHERE group_id = ? AND to_state = 'ACTIVE'"
                    + " AND operation_id = ?", h.f.groupId, h.f.opId)).isEqualTo(1);
            assertThat(h.count("SELECT count(*) FROM autospotting_audit WHERE operation_id = ?", h.f.opId))
                    .isGreaterThan(0);

            // desired_config is the confirmed target; desired_fingerprint is the live owned-field hash.
            JsonNode desired = AwsConversionFixture.JSON.readTree(h.jdbc.queryForObject(
                    "SELECT desired_config::text FROM spot_group WHERE id = ?", String.class, h.f.groupId));
            assertThat(desired).isEqualTo(AwsConversionFixture.JSON.readTree(h.plan.targetJson()));
            assertThat(h.jdbc.queryForObject("SELECT desired_fingerprint FROM spot_group WHERE id = ?",
                    String.class, h.f.groupId))
                    .isEqualTo(AwsFingerprint.of(AwsGroupFacts.from(FakeAws.REGION, h.f.group())));

            // The snapshot was persisted by this op, linked to the group, and holds the ORIGINAL shape.
            assertThat(h.count("SELECT count(*) FROM spot_group_snapshot WHERE captured_by_op = ?", h.f.opId))
                    .isEqualTo(1);
            assertThat(h.count("SELECT count(*) FROM spot_group g JOIN spot_group_snapshot s ON s.id = g.snapshot_id"
                    + " WHERE g.id = ? AND s.captured_by_op = ?", h.f.groupId, h.f.opId)).isEqualTo(1);
            String shape = h.jdbc.queryForObject("SELECT payload->'owned'->>'shape' FROM spot_group_snapshot"
                    + " WHERE captured_by_op = ?", String.class, h.f.opId);
            assertThat(shape).isEqualTo("LAUNCH_TEMPLATE");

            // The writes: one update, then the marker. No refresh and nothing else exists on the client.
            assertThat(h.f.writeCalls()).containsExactly("updateAutoScalingGroup", "createOrUpdateTags");
            assertThat(h.f.log).containsExactly("update", "tags");
            assertThat(h.f.updates.get(0).capacityRebalance()).isTrue();
            h.f.aws.assertReadOnly();
        }
    }

    @Test
    void launchActivitiesBecomeDedupedAnalyticsEventsAndTheConversionIsNeverALaunch() {
        try (AwsOpsHarness h = harness()) {
            h.f.spotInstances.add("i-1");
            Activity launch = Activity.builder().activityId("act-1").statusCode("Successful")
                    .description("Launching a new EC2 instance: i-1").startTime(h.f.clock.instant().minusSeconds(30))
                    .cause("At 2026-11-17T10:00:00Z an instance was started in response to a difference between "
                            + "desired and actual capacity.").build();
            Activity failed = Activity.builder().activityId("act-2").statusCode("Failed")
                    .description("Launching a new EC2 instance").startTime(h.f.clock.instant().minusSeconds(20))
                    .statusMessage("There is no Spot capacity available that matches your request.").build();
            doReturn(DescribeScalingActivitiesResponse.builder().activities(failed, launch).build())
                    .when(h.f.aws.asg).describeScalingActivities(any(DescribeScalingActivitiesRequest.class));

            h.runner.runClaimed(h.lease, h.f.opId);
            // The wait step polls again (a resumed op): the same activities collapse to the same rows.
            AwsLaunchEventRecorder recorder = new AwsLaunchEventRecorder(h.jdbc);
            int again = recorder.record(h.f.groupId, List.of(failed, launch), h.f.clock.instant());

            assertThat(again).isZero();
            assertThat(h.count("SELECT count(*) FROM spot_group_event WHERE group_id = ? AND event_type = 'LAUNCH'"
                    + " AND event_key = 'act-1'", h.f.groupId)).isEqualTo(1);
            assertThat(h.jdbc.queryForObject("SELECT failure_class FROM spot_group_event WHERE group_id = ?"
                    + " AND event_type = 'LAUNCH_FAILED'", String.class, h.f.groupId)).isEqualTo("SPOT_CAPACITY");
            // The configuration change itself is audit, never an event row (AC-HLT-06).
            assertThat(h.count("SELECT count(*) FROM spot_group_event WHERE group_id = ?", h.f.groupId)).isEqualTo(2);
        }
    }

    @Test
    void anOpThatReachesTheProviderWithTheSwitchesOffIsParkedAndNothingRuns() {
        try (AwsOpsHarness h = new AwsOpsHarness(FakeAws.asg("web-asg").build(), OperationKind.ENABLE,
                GroupState.APPLYING, GroupState.UNMANAGED, "conservative", false)) {
            h.runner.runClaimed(h.lease, h.f.opId);

            assertThat(h.opStatus()).isEqualTo("WAITING");
            assertThat(h.opWaitReason()).isEqualTo("SWITCH");
            assertThat(h.count("SELECT count(*) FROM spot_operation_step WHERE operation_id = ?", h.f.opId)).isZero();
            assertThat(h.f.writeCalls()).isEmpty();
            h.f.aws.assertReadOnly();
        }
    }

    @Test
    void aFailedPermissionCheckEndsTheOpWithoutAnyWriteAndLeavesTheGroupWhereItWas() {
        try (AwsOpsHarness h = harness()) {
            org.mockito.Mockito.doAnswer(inv -> {
                software.amazon.awssdk.services.iam.model.SimulatePrincipalPolicyRequest r = inv.getArgument(0);
                return software.amazon.awssdk.services.iam.model.SimulatePrincipalPolicyResponse.builder()
                        .evaluationResults(r.actionNames().stream().map(a ->
                                software.amazon.awssdk.services.iam.model.EvaluationResult.builder()
                                        .evalActionName(a).evalDecision("implicitDeny").build()).toList()).build();
            }).when(h.f.aws.iam).simulatePrincipalPolicy(
                    any(software.amazon.awssdk.services.iam.model.SimulatePrincipalPolicyRequest.class));

            h.runner.runClaimed(h.lease, h.f.opId);

            assertThat(h.opStatus()).isEqualTo("FAILED");
            assertThat(h.opError()).isEqualTo("PERMISSION_MISSING");
            assertThat(h.groupState()).isEqualTo("UNMANAGED"); // back to prior_state
            assertThat(h.f.writeCalls()).isEmpty();
            assertThat(h.count("SELECT count(*) FROM spot_group_snapshot WHERE group_id = ?", h.f.groupId)).isZero();
            assertThat(h.jdbc.queryForObject("SELECT first_mutation_seq FROM spot_operation WHERE id = ?",
                    Integer.class, h.f.opId)).isNull();
        }
    }
}
