package com.xammer.cloud.service.autospotting.aws;

import com.fasterxml.jackson.databind.JsonNode;
import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.OperationKind;
import com.xammer.cloud.service.autospotting.core.AutoSpottingApiException;
import com.xammer.cloud.service.autospotting.spi.PlanOptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;
import software.amazon.awssdk.services.autoscaling.model.InstanceMaintenancePolicy;
import software.amazon.awssdk.services.autoscaling.model.InstancesDistribution;
import software.amazon.awssdk.services.autoscaling.model.LaunchTemplate;
import software.amazon.awssdk.services.autoscaling.model.LaunchTemplateOverrides;
import software.amazon.awssdk.services.autoscaling.model.LaunchTemplateSpecification;
import software.amazon.awssdk.services.autoscaling.model.MixedInstancesPolicy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

/**
 * The AWS REVERT matrix of phase 23b ({@code impl/phase-23b/revert-matrix.md}), one row per test, through the
 * production path: the real PlanService (kind/state gate, NOT_MANAGED, AS_GROUP_BUSY, the provider plan), the real
 * ConfirmService (every listed warning acknowledged; any blocker refused) and the real OperationRunner on the AWS fake.
 * Each row asserts the plan result, the confirm outcome, the steps that ran, the end state, what is left on the ASG
 * (mixed policy and its share, XamOps' 100/200, its added types, the marker tag) and the ownership markers after.
 * Spot: a row whose drain is open has Spot instances in the fake; the drain steps must run before the restore.
 */
@Tag("containers")
class AwsRevertMatrixIT {

    enum Snap { R, M, U }

    enum Perm { OK, PR, CF }

    private static final List<String> TYPES = List.of("m5.large", "m5a.large");
    private static final List<String> ALL_STEPS = List.of("VERIFY_PRECONDITIONS", "DRAIN_SPOT", "WAIT_SPOT_DRAINED",
            "RESTORE_SHAPE", "VERIFY_REVERTED", "REMOVE_MARKER");
    private static final List<String> DRAIN_STEPS = List.of("VERIFY_PRECONDITIONS", "DRAIN_SPOT", "WAIT_SPOT_DRAINED",
            "VERIFY_REVERTED", "REMOVE_MARKER");

    /** What the ASG and the row look like after the row ran. */
    record Expect(String plan, List<String> warnings, boolean confirmed, List<String> steps, String endState,
                  boolean mip, boolean xamopsPolicy, boolean tag, boolean markersKept, String cogReason,
                  String blockerTextExcludes, String blockerTextIncludes) {
    }

    record Row(String id, GroupState state, String cog, boolean drainOpen, Snap snap, Perm perm, String setup,
               Expect expect) {
        @Override
        public String toString() {
            return id + " " + state + " cog=" + cog + " drain=" + (drainOpen ? "open" : "done") + " snap=" + snap
                    + " perm=" + perm + (setup == null ? "" : " " + setup);
        }
    }

    private static Expect restored(List<String> warnings) {
        return new Expect("RESTORE", warnings, true, ALL_STEPS, "REVERTED", false, false, false, false, null, null, null);
    }

    private static Expect restoredOnly(List<String> warnings) {
        return new Expect("RESTORE_ONLY", warnings, true, ALL_STEPS, "REVERTED", false, false, false, false, null, null, null);
    }

    private static Expect drained(List<String> warnings, String reason) {
        return new Expect("DRAIN_ONLY", warnings, true, DRAIN_STEPS, "REVERTED", true, true, false, true, reason, null, null);
    }

    private static Expect blocked(String code, String endState, String excludes) {
        return blocked(code, endState, excludes, null);
    }

    private static Expect blocked(String code, String endState, String excludes, String includes) {
        return new Expect("BLOCKED:" + code, List.of(), false, List.of(), endState, true, true, true, true, null, excludes,
                includes);
    }

    /** A FAILED group's blocked plan still carries REVERT_FROM_FAILED. */
    private static Expect failed(Expect e) {
        return new Expect(e.plan(), List.of("REVERT_FROM_FAILED"), e.confirmed(), e.steps(), e.endState(), e.mip(),
                e.xamopsPolicy(), e.tag(), e.markersKept(), e.cogReason(), e.blockerTextExcludes(),
                e.blockerTextIncludes());
    }

    private static Expect refusedAtPlan(String code, String endState) {
        return new Expect("409:" + code, List.of(), false, List.of(), endState, false, false, false, false, null, null, null);
    }

    static Stream<Row> rows() {
        List<Row> r = new ArrayList<>();
        // A: managed groups, no configOnGroup.
        for (GroupState s : List.of(GroupState.ACTIVE, GroupState.DEGRADED, GroupState.PAUSED)) {
            r.add(new Row("A1", s, null, true, Snap.R, Perm.OK, null, restored(List.of())));
        }
        r.add(new Row("A2", GroupState.ACTIVE, null, true, Snap.R, Perm.PR, null,
                drained(List.of("REVERT_NEEDS_PASSROLE"), "PASSROLE_DENIED")));
        r.add(new Row("A3", GroupState.ACTIVE, null, true, Snap.R, Perm.CF, null,
                drained(List.of("REVERT_CHECK_FAILED"), "CHECK_FAILED")));
        r.add(new Row("A4", GroupState.ACTIVE, null, true, Snap.M, Perm.OK, null,
                drained(List.of("ORIGINAL_UNAVAILABLE"), "ORIGINAL_UNAVAILABLE")));
        r.add(new Row("A4u", GroupState.ACTIVE, null, true, Snap.U, Perm.OK, null,
                drained(List.of("ORIGINAL_UNAVAILABLE"), "ORIGINAL_UNAVAILABLE")));
        for (Perm perm : List.of(Perm.PR, Perm.CF)) {
            // The unreadable original is drained whatever the permission check says (no PassRole is needed).
            r.add(new Row("A4p", GroupState.ACTIVE, null, true, Snap.M, perm, null,
                    drained(List.of("ORIGINAL_UNAVAILABLE"), "ORIGINAL_UNAVAILABLE")));
        }
        // A9: already at 100% On-Demand with no Spot: only bookkeeping is left (marker removed, reason recorded).
        r.add(new Row("A9", GroupState.ACTIVE, null, false, Snap.M, Perm.OK, null,
                drained(List.of("ORIGINAL_UNAVAILABLE"), "ORIGINAL_UNAVAILABLE")));
        r.add(new Row("A5", GroupState.FALLBACK, null, false, Snap.R, Perm.OK, null, restored(List.of())));
        r.add(new Row("A6", GroupState.APPLYING, null, true, Snap.R, Perm.OK, "enable in flight", restored(List.of())));
        r.add(new Row("A7", GroupState.FAILED, null, true, Snap.R, Perm.OK, null, restored(List.of("REVERT_FROM_FAILED"))));
        r.add(new Row("A8", GroupState.FAILED, null, true, Snap.M, Perm.OK, null,
                drained(List.of("REVERT_FROM_FAILED", "ORIGINAL_UNAVAILABLE"), "ORIGINAL_UNAVAILABLE")));
        // B: REVERTED after a drain-only revert (configOnGroup).
        r.add(new Row("B1", GroupState.REVERTED, "PASSROLE_DENIED", false, Snap.R, Perm.OK, null, restoredOnly(List.of())));
        r.add(new Row("B2", GroupState.REVERTED, "PASSROLE_DENIED", false, Snap.R, Perm.PR, null,
                blocked("REVERT_NEEDS_PASSROLE", "REVERTED", null)));
        r.add(new Row("B3", GroupState.REVERTED, "CHECK_FAILED", false, Snap.R, Perm.CF, null,
                blocked("REVERT_CHECK_FAILED", "REVERTED", "PassRole", "iam:SimulatePrincipalPolicy")));
        r.add(new Row("B4", GroupState.REVERTED, "PASSROLE_DENIED", false, Snap.M, Perm.OK, null,
                blocked("SNAPSHOT_MISSING", "REVERTED", null)));
        r.add(new Row("B5", GroupState.REVERTED, "CHECK_FAILED", true, Snap.R, Perm.OK, null, restoredOnly(List.of())));
        r.add(new Row("B6", GroupState.REVERTED, null, false, Snap.R, Perm.OK, null,
                refusedAtPlan("NOT_MANAGED", "REVERTED")));
        // C: FAILED with configOnGroup (a revert mutated and did not restore).
        r.add(new Row("C1", GroupState.FAILED, "", false, Snap.R, Perm.OK, null, restoredOnly(List.of("REVERT_FROM_FAILED"))));
        r.add(new Row("C2", GroupState.FAILED, "", true, Snap.R, Perm.OK, null, restoredOnly(List.of("REVERT_FROM_FAILED"))));
        r.add(new Row("C3", GroupState.FAILED, "", false, Snap.R, Perm.PR, null,
                failed(blocked("REVERT_NEEDS_PASSROLE", "FAILED", null))));
        r.add(new Row("C3c", GroupState.FAILED, "", false, Snap.R, Perm.CF, null,
                failed(blocked("REVERT_CHECK_FAILED", "FAILED", "PassRole", "iam:SimulatePrincipalPolicy"))));
        r.add(new Row("C4", GroupState.FAILED, "", true, Snap.R, Perm.PR, null,
                drained(List.of("REVERT_FROM_FAILED", "REVERT_NEEDS_PASSROLE"), "PASSROLE_DENIED")));
        r.add(new Row("C5", GroupState.FAILED, "", false, Snap.M, Perm.OK, null,
                failed(blocked("SNAPSHOT_MISSING", "FAILED", null))));
        r.add(new Row("C6", GroupState.FAILED, "", true, Snap.M, Perm.OK, null,
                drained(List.of("REVERT_FROM_FAILED", "ORIGINAL_UNAVAILABLE"), "ORIGINAL_UNAVAILABLE")));
        // E: the first ENABLE mutated the group but never wrote desired_config (review High #1).
        r.add(new Row("E1", GroupState.APPLYING, null, true, Snap.R, Perm.PR, "enable in flight, no desired",
                drained(List.of("REVERT_NEEDS_PASSROLE"), "PASSROLE_DENIED")));
        r.add(new Row("E2", GroupState.FAILED, null, true, Snap.M, Perm.OK, "failed enable, no desired",
                drained(List.of("REVERT_FROM_FAILED", "ORIGINAL_UNAVAILABLE"), "ORIGINAL_UNAVAILABLE")));
        r.add(new Row("E3", GroupState.FAILED, null, true, Snap.R, Perm.CF, "failed enable, no desired",
                drained(List.of("REVERT_FROM_FAILED", "REVERT_CHECK_FAILED"), "CHECK_FAILED")));
        // E4: a later CONFIG_APPLY failed before it mutated; the gate is still built from the ENABLE that did (review #10).
        r.add(new Row("E4", GroupState.FAILED, null, true, Snap.R, Perm.CF, "failed enable, no desired, unmutated apply",
                drained(List.of("REVERT_FROM_FAILED", "REVERT_CHECK_FAILED"), "CHECK_FAILED")));
        // E5: no conversion target at all; the gate still records configOnGroup with a fingerprint (review #11).
        r.add(new Row("E5", GroupState.FAILED, null, true, Snap.R, Perm.CF, "no desired, no conversion target",
                drained(List.of("REVERT_FROM_FAILED", "REVERT_CHECK_FAILED"), "CHECK_FAILED")));
        // F: the running Spot count decides the drain (review High #2, #3).
        r.add(new Row("F1", GroupState.REVERTED, "PASSROLE_DENIED", false, Snap.M, Perm.OK, "spot unreadable",
                blocked("SPOT_COUNT_UNREADABLE", "REVERTED", "already at 100%")));
        // F2/F3: share 100 with Spot still running. Auto Scaling won't replace them on its own (an unchanged update
        // starts nothing, and AD-01 rules out an instance refresh), so the plan is blocked (review High #2, option A).
        r.add(new Row("F2", GroupState.FAILED, "", true, Snap.R, Perm.OK, "share 100, spot running",
                failed(blocked("REVERT_SPOT_STUCK", "FAILED", null, "still running at 100% On-Demand"))));
        r.add(new Row("F3", GroupState.FAILED, "", true, Snap.R, Perm.PR, "share 100, spot running",
                failed(blocked("REVERT_SPOT_STUCK", "FAILED", null, "still running at 100% On-Demand"))));
        // D: not managed / busy.
        r.add(new Row("D1", GroupState.UNMANAGED, null, false, Snap.M, Perm.OK, null, refusedAtPlan("NOT_MANAGED", "UNMANAGED")));
        r.add(new Row("D2", GroupState.REVERTING, null, true, Snap.R, Perm.OK, null, refusedAtPlan("AS_GROUP_BUSY", "REVERTING")));
        r.add(new Row("D3", GroupState.PLANNED, null, false, Snap.M, Perm.OK, null, refusedAtPlan("NOT_MANAGED", "PLANNED")));
        r.add(new Row("D4", GroupState.PLANNED, null, false, Snap.M, Perm.OK, "enable queued",
                refusedAtPlan("NOT_MANAGED", "PLANNED")));
        return r.stream();
    }

    // ---- fixtures ----

    private static InstanceMaintenancePolicy owned() {
        return InstanceMaintenancePolicy.builder().minHealthyPercentage(100).maxHealthyPercentage(200).build();
    }

    /** The live group: XamOps' MIP (share 50 when the drain is open, else 100), its types, 100/200 and the marker. */
    private static AutoScalingGroup live(boolean drainOpen) {
        return FakeAws.asg("web-asg").launchTemplate((LaunchTemplateSpecification) null).capacityRebalance(true)
                .tags(FakeAws.tag(AwsGroupFacts.MARKER_TAG, "marker")).instanceMaintenancePolicy(owned())
                .mixedInstancesPolicy(MixedInstancesPolicy.builder().launchTemplate(LaunchTemplate.builder()
                                .launchTemplateSpecification(LaunchTemplateSpecification.builder().launchTemplateId("lt-0abc")
                                        .version("$Default").build())
                                .overrides(TYPES.stream().map(t -> LaunchTemplateOverrides.builder().instanceType(t).build())
                                        .toList()).build())
                        .instancesDistribution(InstancesDistribution.builder().onDemandAllocationStrategy("prioritized")
                                .onDemandBaseCapacity(1).onDemandPercentageAboveBaseCapacity(drainOpen ? 50 : 100)
                                .spotAllocationStrategy("price-capacity-optimized").build()).build())
                .build();
    }

    private static String desired(String cog) {
        return "{\"shape\":\"MIXED_INSTANCES_POLICY\",\"launchTemplate\":{\"id\":\"lt-0abc\",\"version\":\"$Default\"},"
                + "\"overrides\":" + TYPES.stream().map(t -> "\"" + t + "\"").toList()
                + ",\"instancesDistribution\":{\"onDemandAllocationStrategy\":\"prioritized\",\"onDemandBaseCapacity\":1,"
                + "\"onDemandPercentageAboveBaseCapacity\":50,\"spotAllocationStrategy\":\"price-capacity-optimized\"},"
                + "\"capacityRebalance\":true,\"instanceMaintenancePolicy\":{\"owned\":true,\"min\":100,\"max\":200}"
                + (cog == null ? "" : ",\"" + PlanOptions.CONFIG_ON_GROUP + "\":true"
                        + (cog.isEmpty() ? "" : ",\"" + PlanOptions.CONFIG_ON_GROUP_REASON + "\":\"" + cog + "\""))
                + "}";
    }

    private static void perms(AwsConversionFixture fx, Perm perm) {
        if (perm == Perm.CF) {
            doThrow(AwsServiceException.builder().statusCode(403).awsErrorDetails(AwsErrorDetails.builder()
                    .errorCode("AccessDenied").build()).build()).when(fx.aws.iam).simulatePrincipalPolicy(any(
                    software.amazon.awssdk.services.iam.model.SimulatePrincipalPolicyRequest.class));
            return;
        }
        if (perm == Perm.PR) {
            doReturn(software.amazon.awssdk.services.ec2.model.DescribeLaunchTemplateVersionsResponse.builder()
                    .launchTemplateVersions(software.amazon.awssdk.services.ec2.model.LaunchTemplateVersion.builder()
                            .versionNumber(7L).launchTemplateData(software.amazon.awssdk.services.ec2.model
                                    .ResponseLaunchTemplateData.builder().imageId("ami-0aaa").instanceType("m5.large")
                                    .iamInstanceProfile(software.amazon.awssdk.services.ec2.model
                                            .LaunchTemplateIamInstanceProfileSpecification.builder().name("web-profile")
                                            .build()).build()).build()).build())
                    .when(fx.aws.ec2).describeLaunchTemplateVersions(any(
                            software.amazon.awssdk.services.ec2.model.DescribeLaunchTemplateVersionsRequest.class));
            doReturn(software.amazon.awssdk.services.iam.model.GetInstanceProfileResponse.builder().instanceProfile(
                    software.amazon.awssdk.services.iam.model.InstanceProfile.builder().roles(
                            software.amazon.awssdk.services.iam.model.Role.builder()
                                    .arn("arn:aws:iam::111122223333:role/web").build()).build()).build())
                    .when(fx.aws.iam).getInstanceProfile(any(
                            software.amazon.awssdk.services.iam.model.GetInstanceProfileRequest.class));
            doAnswer(inv -> {
                software.amazon.awssdk.services.iam.model.SimulatePrincipalPolicyRequest q = inv.getArgument(0);
                return software.amazon.awssdk.services.iam.model.SimulatePrincipalPolicyResponse.builder()
                        .evaluationResults(q.actionNames().stream().map(a -> software.amazon.awssdk.services.iam.model
                                .EvaluationResult.builder().evalActionName(a)
                                .evalDecision("iam:PassRole".equals(a) ? "implicitDeny" : "allowed").build()).toList())
                        .build();
            }).when(fx.aws.iam).simulatePrincipalPolicy(any(
                    software.amazon.awssdk.services.iam.model.SimulatePrincipalPolicyRequest.class));
        }
    }

    private static AwsOpsHarness harness(Row row) {
        if ("enable queued".equals(row.setup())) {
            AwsOpsHarness queued = new AwsOpsHarness(FakeAws.asg("web-asg").build(), OperationKind.ENABLE,
                    GroupState.PLANNED, GroupState.PLANNED, "conservative");
            // The harness claims its op; the row is a confirmed ENABLE that has not started, so put it back to QUEUED.
            queued.jdbc.update("UPDATE spot_operation SET status = 'QUEUED' WHERE id = ?", queued.f.opId);
            return queued;
        }
        boolean converted = row.state() != GroupState.UNMANAGED && row.state() != GroupState.PLANNED;
        boolean share100 = row.setup() != null && row.setup().contains("share 100");
        AutoScalingGroup group = converted ? live(row.drainOpen() && !share100) : FakeAws.asg("web-asg").build();
        String configMode = row.state() == GroupState.FALLBACK ? "FALLBACK" : "MANAGED";
        String pause = row.state() == GroupState.PAUSED ? "OPERATOR" : null;
        boolean noDesired = row.setup() != null && row.setup().contains("no desired");
        AwsOpsHarness h = new AwsOpsHarness(group, row.state(), configMode,
                converted && !noDesired ? desired(row.cog()) : null, null, pause,
                pause == null ? null : GroupState.ACTIVE);
        if (noDesired && row.state() == GroupState.FAILED && !row.setup().contains("no conversion target")) {
            h.insertFailedConversion(OperationKind.ENABLE, desired(null));
        }
        if (row.setup() != null && row.setup().contains("unmutated apply")) {
            // A different target that never reached the cloud: if it were picked, the owned-policy marker would go.
            h.insertUnmutatedConversion(OperationKind.CONFIG_APPLY, desired(null).replace(
                    ",\"instanceMaintenancePolicy\":{\"owned\":true,\"min\":100,\"max\":200}", ""));
        }
        if (row.snap() == Snap.R) {
            h.linkSnapshot(FakeAws.asg("web-asg").build());
        } else if (row.snap() == Snap.U) {
            UUID bad = UUID.randomUUID();
            h.jdbc.update("INSERT INTO spot_group_snapshot (id, group_id, provider, schema_version, exact, payload,"
                    + " payload_sha256, captured_at, captured_by_op) VALUES (?, ?, 'AWS', 1, true,"
                    + " '{\"junk\":true}'::jsonb, 'x', now(), ?)", bad, h.f.groupId, UUID.randomUUID());
            h.jdbc.update("UPDATE spot_group SET snapshot_id = ? WHERE id = ?", bad, h.f.groupId);
        }
        perms(h.f, row.perm());
        if ("spot unreadable".equals(row.setup())) {
            // The first activities read (the plan's Spot count) fails once; later reads (the fingerprint) succeed.
            java.util.concurrent.atomic.AtomicBoolean failed = new java.util.concurrent.atomic.AtomicBoolean();
            doAnswer(inv -> {
                if (failed.compareAndSet(false, true)) {
                    throw AwsServiceException.builder().statusCode(500).awsErrorDetails(AwsErrorDetails.builder()
                            .errorCode("InternalFailure").build()).build();
                }
                return software.amazon.awssdk.services.autoscaling.model.DescribeScalingActivitiesResponse.builder()
                        .activities(List.of()).build();
            }).when(h.f.aws.asg).describeScalingActivities(any(
                    software.amazon.awssdk.services.autoscaling.model.DescribeScalingActivitiesRequest.class));
        }
        if (row.drainOpen()) {
            h.f.spotInstances.add("i-3");
            h.f.spotInstances.add("i-4");
        }
        if (row.setup() != null && row.setup().startsWith("enable in flight")) {
            h.startEnableInFlight(desired(null));
        }
        return h;
    }

    private static List<String> doneSteps(AwsOpsHarness h, UUID op) {
        return h.jdbc.queryForList("SELECT name FROM spot_operation_step WHERE operation_id = ? AND status = 'DONE'"
                + " ORDER BY seq", String.class, op);
    }

    // ---- the matrix ----

    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    void everyRevertRowEndsWhereTheMatrixSays(Row row) {
        Expect e = row.expect();
        try (AwsOpsHarness h = harness(row)) {
            // Plan through the real PlanService.
            com.xammer.cloud.domain.autospotting.SpotPlan plan;
            try {
                plan = h.planThroughService(OperationKind.REVERT);
            } catch (AutoSpottingApiException ex) {
                assertThat("409:" + ex.getCode()).as("plan result").isEqualTo(e.plan());
                assertThat(h.groupState()).as("end state").isEqualTo(e.endState());
                return;
            }
            JsonNode body = h.f.json(plan.getBody());
            List<String> blockerCodes = new ArrayList<>();
            body.path("eligibility").path("blockers").forEach(b -> blockerCodes.add(b.path("code").asText()));
            List<String> warningIds = new ArrayList<>();
            body.path("eligibility").path("warnings").forEach(w -> warningIds.add(w.path("id").asText()));
            String result = blockerCodes.isEmpty() ? body.path("target").path("mode").asText()
                    : "BLOCKED:" + blockerCodes.get(0);
            assertThat(result).as("plan result (blockers " + blockerCodes + ", warnings " + warningIds + ")")
                    .isEqualTo(e.plan());
            assertThat(warningIds).as("warnings").containsExactlyInAnyOrderElementsOf(e.warnings());
            if (body.path("target").has("acks")) {
                List<String> acks = new ArrayList<>();
                body.path("target").path("acks").forEach(a -> acks.add(a.asText()));
                assertThat(acks).as("target.acks lists what confirm requires (review #10)")
                        .containsExactlyInAnyOrderElementsOf(warningIds);
            }
            if ("ORIGINAL_UNAVAILABLE".equals(e.cogReason())) {
                assertOriginalUnavailableContract(h, body);
            }
            if (e.blockerTextIncludes() != null) {
                assertThat(body.path("eligibility").path("blockers").get(0).path("text").asText())
                        .as("blocker text").contains(e.blockerTextIncludes());
            }
            if (e.blockerTextExcludes() != null) {
                assertThat(body.path("eligibility").path("blockers").get(0).path("text").asText())
                        .as("blocker text").doesNotContain(e.blockerTextExcludes());
            }
            int opsBefore = h.jdbc.queryForObject("SELECT count(*) FROM spot_operation WHERE kind = 'REVERT'", Integer.class);
            UUID op;
            try {
                // A blocked row confirms with no acknowledgements: the answer is the blocker, never ACK_REQUIRED.
                op = e.confirmed() ? h.confirmPlan(plan.getId()) : h.confirmPlan(plan.getId(), List.of());
            } catch (AutoSpottingApiException ex) {
                assertThat(e.confirmed()).as("confirm refused with " + ex.getCode()).isFalse();
                assertThat(ex.getStatus().value()).isEqualTo(422);
                assertThat(ex.getCode()).as("the plan's first blocker").isEqualTo(blockerCodes.get(0));
                assertThat(ex.getMessage()).as("the plan's blocker text")
                        .isEqualTo(body.path("eligibility").path("blockers").get(0).path("text").asText());
                assertThat(h.planStatus(plan.getId())).as("a refused plan does not stay OPEN").isEqualTo("STALE");
                assertThat(h.jdbc.queryForObject("SELECT count(*) FROM spot_operation WHERE kind = 'REVERT'",
                        Integer.class)).as("no revert op").isEqualTo(opsBefore);
                assertAfter(h, e);
                return;
            }
            assertThat(e.confirmed()).as("confirm accepted").isTrue();
            if (row.setup() != null && row.setup().startsWith("enable in flight")) {
                h.store.claimForRun(h.enableOpId);
                h.runner.runClaimed(h.lease, h.enableOpId);
                assertThat(h.jdbc.queryForObject("SELECT status FROM spot_operation WHERE id = ?", String.class,
                        h.enableOpId)).as("the in-flight enable was preempted").isEqualTo("CANCELLED");
            }
            h.store.claimForRun(op);
            h.runner.runClaimed(h.lease, op);
            if (h.f.replacementStarted) {
                h.f.spotInstances.clear(); // a drain that changed the purchase mix replaced Spot with On-Demand
            }
            String status = h.runToEnd(op);
            assertThat(status).as("op (error " + h.jdbc.queryForList("SELECT error_code FROM spot_operation WHERE id = ?",
                    String.class, op) + ")").isEqualTo("SUCCEEDED");
            assertThat(doneSteps(h, op)).as("steps").isEqualTo(e.steps());
            if (!row.drainOpen() && "DRAIN_ONLY".equals(e.plan())) {
                assertThat(h.f.updates).as("the group is already drained: no ASG update, only bookkeeping").isEmpty();
            }
            if (row.drainOpen() && e.steps().contains("DRAIN_SPOT")) {
                // The drain must be a real call, sent before any restore: share 100 and no launch template.
                assertThat(h.f.updates).as("the drain call").isNotEmpty();
                var first = h.f.updates.get(0);
                assertThat(first.mixedInstancesPolicy().instancesDistribution().onDemandPercentageAboveBaseCapacity())
                        .as("first update drains to 100% On-Demand").isEqualTo(100);
                assertThat(first.launchTemplate()).as("the drain carries no launch template").isNull();
            }
            if (row.setup() != null && row.setup().contains("no conversion target")) {
                // No record of what XamOps put on the group: the gate says configuration remains, claims no owned
                // policy it cannot prove, and carries a fingerprint (review #11).
                var g = h.row();
                assertThat(PlanOptions.forGroup(null, g).configOnGroup()).as("configOnGroup").isTrue();
                assertThat(PlanOptions.forGroup(null, g).configOnGroupReason()).isEqualTo("CHECK_FAILED");
                assertThat(AwsMaintenancePolicy.owns(g.getDesiredConfig())).as("no owned-policy claim").isFalse();
                assertThat(g.getDesiredFingerprint()).as("desired_fingerprint").isNotNull();
                assertThat(h.groupState()).isEqualTo(e.endState());
                return;
            }
            assertAfter(h, e);
        }
    }

    /** PD-16: an unavailable original is drained, never presented as a restore. */
    private static void assertOriginalUnavailableContract(AwsOpsHarness h, JsonNode body) {
        assertThat(body.path("target").path("mode").asText()).isEqualTo("DRAIN_ONLY");
        assertThat(body.path("target").path("exact").asBoolean(true)).as("target.exact").isFalse();
        assertThat(body.path("target").path("reasons").toString()).isEqualTo("[\"ORIGINAL_UNAVAILABLE\"]");
        assertThat(body.path("revertPath").path("method").asText()).isEqualTo("DRAIN_ONLY");
        assertThat(body.path("revertPath").path("exact").asBoolean(true)).as("revertPath.exact").isFalse();
        assertThat(body.path("revertPath").path("reasons").toString()).isEqualTo("[\"ORIGINAL_UNAVAILABLE\"]");
        assertThat(body.path("revertPath").path("remediation").asText()).isEqualTo("Contact support");
        assertThat(body.path("target").path("instancesDistribution").path("onDemandPercentageAboveBaseCapacity").asInt())
                .as("the target is the drained live shape").isEqualTo(100);
        var plan = h.provider.createPlan(new com.xammer.cloud.service.autospotting.spi.PlanRequest(
                new com.xammer.cloud.service.autospotting.spi.GroupRef(com.xammer.cloud.domain.autospotting.Provider.AWS,
                        7L, FakeAws.REGION + "/web-asg", null), OperationKind.REVERT, null), h.f.aws.view());
        assertThat(plan.snapshotToStore()).as("no snapshot is stored or claimed").isNull();
        assertThat(plan.eligibility().warnings()).filteredOn(w -> "ORIGINAL_UNAVAILABLE".equals(w.id()))
                .singleElement().satisfies(w -> assertThat(w.text()).contains("Contact support"));
    }

    private static void assertAfter(AwsOpsHarness h, Expect e) {
        assertThat(h.groupState()).as("end state").isEqualTo(e.endState());
        AutoScalingGroup g = h.f.group();
        assertThat(g.mixedInstancesPolicy() != null).as("XamOps' mixed policy left").isEqualTo(e.mip());
        if (e.mip() && e.steps().contains("DRAIN_SPOT")) {
            assertThat(g.mixedInstancesPolicy().instancesDistribution().onDemandPercentageAboveBaseCapacity())
                    .as("drained to 100% On-Demand").isEqualTo(100);
        }
        assertThat(g.instanceMaintenancePolicy() != null).as("100/200 left").isEqualTo(e.xamopsPolicy());
        if (e.mip()) {
            List<String> types = new ArrayList<>();
            g.mixedInstancesPolicy().launchTemplate().overrides().forEach(o -> types.add(o.instanceType()));
            assertThat(types).as("XamOps' types left").containsExactlyInAnyOrderElementsOf(TYPES);
        }
        assertThat(g.tags().stream().anyMatch(t -> AwsGroupFacts.MARKER_TAG.equals(t.key())))
                .as("marker tag left").isEqualTo(e.tag());
        var row = h.row();
        if (row.getDesiredConfig() == null) {
            assertThat(e.markersKept()).as("markers").isFalse();
            return;
        }
        PlanOptions o = PlanOptions.forGroup(null, row);
        assertThat(AwsMaintenancePolicy.owns(row.getDesiredConfig())).as("owned-policy marker kept")
                .isEqualTo(e.markersKept());
        assertThat(o.configOnGroup()).as("configOnGroup").isEqualTo(e.markersKept());
        if (e.cogReason() != null) {
            assertThat(o.configOnGroupReason()).as("configOnGroup reason").isEqualTo(e.cogReason());
        }
    }
}
