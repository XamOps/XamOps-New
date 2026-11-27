package com.xammer.cloud.service.autospotting.aws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.autospotting.CompensationTarget;
import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.OperationKind;
import com.xammer.cloud.domain.autospotting.OperationPhase;
import com.xammer.cloud.domain.autospotting.Provider;
import com.xammer.cloud.domain.autospotting.SpotGroup;
import com.xammer.cloud.service.autospotting.core.AutoSpottingApiException;
import com.xammer.cloud.service.autospotting.spi.GroupRef;
import com.xammer.cloud.service.autospotting.spi.OperationContext;
import com.xammer.cloud.service.autospotting.spi.PlanBody;
import com.xammer.cloud.service.autospotting.spi.PlanRequest;
import com.xammer.cloud.service.autospotting.spi.Step;
import com.xammer.cloud.service.autospotting.spi.StepOutcome;
import com.xammer.cloud.service.autospotting.spi.StepProgram;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;
import software.amazon.awssdk.services.autoscaling.model.InstancesDistribution;
import software.amazon.awssdk.services.autoscaling.model.LaunchTemplate;
import software.amazon.awssdk.services.autoscaling.model.LaunchTemplateOverrides;
import software.amazon.awssdk.services.autoscaling.model.LaunchTemplateSpecification;
import software.amazon.awssdk.services.autoscaling.model.MixedInstancesPolicy;
import software.amazon.awssdk.services.autoscaling.model.UpdateAutoScalingGroupRequest;
import software.amazon.awssdk.services.autoscaling.model.UpdateAutoScalingGroupResponse;
import software.amazon.awssdk.services.ec2.model.DescribeLaunchTemplateVersionsRequest;
import software.amazon.awssdk.services.ec2.model.DescribeLaunchTemplateVersionsResponse;
import software.amazon.awssdk.services.ec2.model.LaunchTemplateIamInstanceProfileSpecification;
import software.amazon.awssdk.services.ec2.model.LaunchTemplateVersion;
import software.amazon.awssdk.services.ec2.model.ResponseLaunchTemplateData;
import software.amazon.awssdk.services.iam.model.EvaluationResult;
import software.amazon.awssdk.services.iam.model.GetInstanceProfileRequest;
import software.amazon.awssdk.services.iam.model.GetInstanceProfileResponse;
import software.amazon.awssdk.services.iam.model.InstanceProfile;
import software.amazon.awssdk.services.iam.model.Role;
import software.amazon.awssdk.services.iam.model.SimulatePrincipalPolicyRequest;
import software.amazon.awssdk.services.iam.model.SimulatePrincipalPolicyResponse;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * The AWS REVERT program over a stubbed SDK (plan-phase-19.md tasks 19.1 and 19.3; AC-REV-01, AC-REV-05,
 * design-aws.md §5.3). No test makes a real AWS call; every assertion about a write reads the write client's
 * recorded calls.
 */
class AwsRestoreProgramTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DESIRED = "{\"shape\":\"MIXED_INSTANCES_POLICY\",\"launchTemplate\":{\"id\":\"lt-0abc\","
            + "\"version\":\"$Default\"},\"overrides\":[\"m5.large\",\"m5a.large\",\"m6i.large\"],"
            + "\"instancesDistribution\":{\"onDemandAllocationStrategy\":\"prioritized\",\"onDemandBaseCapacity\":1,"
            + "\"onDemandPercentageAboveBaseCapacity\":50,\"spotAllocationStrategy\":\"price-capacity-optimized\"},"
            + "\"capacityRebalance\":true}";

    /** The group after ENABLE: a mixed policy over the original template, Capacity Rebalancing on, the marker tag. */
    private static AutoScalingGroup converted(AutoScalingGroup.Builder base) {
        return base.launchTemplate((LaunchTemplateSpecification) null).capacityRebalance(true)
                .tags(FakeAws.tag(AwsGroupFacts.MARKER_TAG, "marker"))
                .mixedInstancesPolicy(MixedInstancesPolicy.builder().launchTemplate(LaunchTemplate.builder()
                        .launchTemplateSpecification(LaunchTemplateSpecification.builder().launchTemplateId("lt-0abc")
                                .version("$Default").build())
                        .overrides(List.of("m5.large", "m5a.large", "m6i.large").stream()
                                .map(t -> LaunchTemplateOverrides.builder().instanceType(t).build()).toList()).build())
                        .instancesDistribution(InstancesDistribution.builder().onDemandAllocationStrategy("prioritized")
                                .onDemandBaseCapacity(1).onDemandPercentageAboveBaseCapacity(50)
                                .spotAllocationStrategy("price-capacity-optimized").build()).build()).build();
    }

    /** The same policy with weight 1 on every override, as the customer's own group had. */
    private static AutoScalingGroup weighted(AutoScalingGroup g) {
        LaunchTemplate lt = g.mixedInstancesPolicy().launchTemplate();
        return g.toBuilder().mixedInstancesPolicy(g.mixedInstancesPolicy().toBuilder().launchTemplate(lt.toBuilder()
                .overrides(lt.overrides().stream().map(o -> o.toBuilder().weightedCapacity("1").build()).toList())
                .build()).build()).build();
    }

    private static AutoScalingGroup converted() {
        return converted(FakeAws.asg("web-asg"));
    }

    private static AwsConversionFixture fixture(AutoScalingGroup live, AutoScalingGroup snapshotOf) {
        AwsConversionFixture f = new AwsConversionFixture(live, true);
        String payload = AwsSnapshotCodec.encode(FakeAws.REGION, snapshotOf, AwsSnapshotCodec.Context.none(), false)
                .payloadJson();
        f.snapshots.linked = new AwsSnapshotStore.Stored(UUID.randomUUID(), f.groupId, true, payload,
                AwsFingerprint.sha256(payload));
        when(f.facts.planLiveFingerprint(f.planId)).thenReturn(
                Optional.of(AwsFingerprint.of(AwsGroupFacts.from(FakeAws.REGION, f.group()))));
        when(f.facts.planConfirmAcks(f.planId)).thenReturn(Optional.of("[]"));
        return f;
    }

    private static SpotGroup row(AwsConversionFixture f, GroupState state) {
        SpotGroup g = f.spotGroup();
        g.setState(state);
        g.setDesiredConfig(DESIRED);
        return g;
    }

    private static OperationContext revert(AwsConversionFixture f, String targetJson) {
        return new OperationContext(f.opId, OperationKind.REVERT, row(f, GroupState.REVERTING), targetJson, null,
                OperationPhase.FORWARD, f.planId, CompensationTarget.NONE);
    }

    private static List<String> names(StepProgram p) {
        return p.steps().stream().map(Step::name).toList();
    }

    private static StepOutcome run(StepProgram p, int index, AwsConversionFixture f) {
        return p.at(index).execute(f.stepContext(index));
    }

    /** Stub the template version to have an instance profile, and deny {@code iam:PassRole} when asked. */
    private static void instanceProfile(AwsConversionFixture f, boolean denyPassRole) {
        doReturn(DescribeLaunchTemplateVersionsResponse.builder().launchTemplateVersions(
                LaunchTemplateVersion.builder().versionNumber(7L).launchTemplateData(ResponseLaunchTemplateData.builder()
                        .imageId("ami-0aaa").instanceType("m5.large").iamInstanceProfile(
                                LaunchTemplateIamInstanceProfileSpecification.builder().name("web-profile").build())
                        .build()).build()).build()).when(f.aws.ec2)
                .describeLaunchTemplateVersions(any(DescribeLaunchTemplateVersionsRequest.class));
        doReturn(GetInstanceProfileResponse.builder().instanceProfile(InstanceProfile.builder().roles(
                Role.builder().arn("arn:aws:iam::111122223333:role/web").build()).build()).build())
                .when(f.aws.iam).getInstanceProfile(any(GetInstanceProfileRequest.class));
        doAnswer(inv -> {
            SimulatePrincipalPolicyRequest r = inv.getArgument(0);
            return SimulatePrincipalPolicyResponse.builder().evaluationResults(r.actionNames().stream()
                    .map(a -> EvaluationResult.builder().evalActionName(a).evalDecision(
                            denyPassRole && a.equals("iam:PassRole") ? "implicitDeny" : "allowed").build()).toList())
                    .build();
        }).when(f.aws.iam).simulatePrincipalPolicy(any(SimulatePrincipalPolicyRequest.class));
    }

    private static PlanBody plan(AwsConversionFixture f, GroupState state) {
        when(f.groupLookup.live(eq(Provider.AWS), eq(7L), anyString())).thenReturn(Optional.of(row(f, state)));
        return f.provider.createPlan(new PlanRequest(new GroupRef(Provider.AWS, 7L, FakeAws.REGION + "/web-asg", null),
                OperationKind.REVERT, null), f.aws.view());
    }

    private static JsonNode json(String text) throws IOException {
        return JSON.readTree(text);
    }

    private static JsonNode golden(String name) throws IOException {
        try (InputStream in = AwsRestoreProgramTest.class.getResourceAsStream("/autospotting/aws/golden/" + name + ".json")) {
            assertThat(in).as("golden file " + name).isNotNull();
            return JSON.readTree(in);
        }
    }

    // ---- AD-08: the owned maintenance policy is restored exactly ----

    private static final String DESIRED_OWNED = DESIRED.substring(0, DESIRED.length() - 1)
            + ",\"instanceMaintenancePolicy\":{\"owned\":true,\"min\":100,\"max\":200}}";

    private static software.amazon.awssdk.services.autoscaling.model.InstanceMaintenancePolicy imp(int min, int max) {
        return software.amazon.awssdk.services.autoscaling.model.InstanceMaintenancePolicy.builder()
                .minHealthyPercentage(min).maxHealthyPercentage(max).build();
    }

    private static OperationContext revertOwned(AwsConversionFixture f) {
        SpotGroup g = row(f, GroupState.REVERTING);
        g.setDesiredConfig(DESIRED_OWNED);
        return new OperationContext(f.opId, OperationKind.REVERT, g, "{\"mode\":\"RESTORE\"}", null,
                OperationPhase.FORWARD, f.planId, CompensationTarget.NONE);
    }

    private static void runAll(StepProgram p, AwsConversionFixture f) {
        for (int i = 0; i < p.steps().size(); i++) {
            assertThat(run(p, i, f)).as("step " + p.at(i).name()).isInstanceOf(StepOutcome.Done.class);
        }
    }

    @Test
    void aPolicyXamOpsSetOnAGroupThatHadNoneIsClearedWithMinusOneAtRevert() {
        AwsConversionFixture f = fixture(converted().toBuilder().instanceMaintenancePolicy(imp(100, 200)).build(),
                FakeAws.asg("web-asg").build());
        runAll(f.provider.restore(revertOwned(f), null), f);
        UpdateAutoScalingGroupRequest restore = f.updates.get(f.updates.size() - 1);
        assertThat(restore.instanceMaintenancePolicy().minHealthyPercentage()).isEqualTo(-1);
        assertThat(restore.instanceMaintenancePolicy().maxHealthyPercentage()).isEqualTo(-1);
        assertThat(f.group().instanceMaintenancePolicy()).as("the original, 'absent', is back").isNull();
        assertThat(AwsRestoreRequests.forbidden(restore)).isEmpty();
    }

    @Test
    void aPolicyTheCustomerChangedAfterTheConversionIsKeptAndListedAsChangedOutside() throws IOException {
        AwsConversionFixture f = fixture(converted().toBuilder().instanceMaintenancePolicy(imp(80, 150)).build(),
                FakeAws.asg("web-asg").build());
        runAll(f.provider.restore(revertOwned(f), null), f);
        assertThat(f.updates.stream().anyMatch(u -> u.instanceMaintenancePolicy() != null)).isFalse();
        assertThat(f.group().instanceMaintenancePolicy().minHealthyPercentage()).isEqualTo(80);
        AwsSnapshotCodec.Owned owned = AwsSnapshotCodec.decodeOwned(f.snapshots.linked.payload()).orElseThrow();
        AwsThreeWayDiff.Result diff = AwsThreeWayDiff.compute(owned, json(DESIRED_OWNED),
                f.group().toBuilder().instanceMaintenancePolicy(imp(80, 150)).build(), null, false);
        assertThat(diff.kept()).extracting(AwsThreeWayDiff.Kept::field).contains("instanceMaintenancePolicy");
    }

    @Test
    void aSnapshotTakenBeforeThePolicyWasRecordedLeavesThePolicyAlone() throws IOException {
        AutoScalingGroup live = converted().toBuilder().instanceMaintenancePolicy(imp(100, 200)).build();
        AwsConversionFixture f = fixture(live, FakeAws.asg("web-asg").build());
        String old = f.snapshots.linked.payload().replace(",\"instanceMaintenancePolicy\":{\"state\":\"ABSENT\"}", "");
        assertThat(old).doesNotContain("instanceMaintenancePolicy");
        f.snapshots.linked = new AwsSnapshotStore.Stored(UUID.randomUUID(), f.groupId, true, old, AwsFingerprint.sha256(old));
        runAll(f.provider.restore(revertOwned(f), null), f);
        assertThat(f.updates.stream().anyMatch(u -> u.instanceMaintenancePolicy() != null)).isFalse();
        assertThat(f.group().instanceMaintenancePolicy().maxHealthyPercentage()).isEqualTo(200);
    }

    @Test
    void aCustomerPolicyXamOpsNeverOwnedIsNotTouchedAtRevert() {
        AutoScalingGroup original = FakeAws.asg("web-asg").instanceMaintenancePolicy(imp(100, 150)).build();
        AwsConversionFixture f = fixture(converted().toBuilder().instanceMaintenancePolicy(imp(100, 150)).build(), original);
        runAll(f.provider.restore(revert(f, "{\"mode\":\"RESTORE\"}"), null), f);
        assertThat(f.updates.stream().anyMatch(u -> u.instanceMaintenancePolicy() != null)).isFalse();
        assertThat(f.group().instanceMaintenancePolicy().maxHealthyPercentage()).isEqualTo(150);
    }

    @Test
    void theRevertPlanShowsTheRestoreValueOfThePolicy() throws IOException {
        AwsConversionFixture f = fixture(converted().toBuilder().instanceMaintenancePolicy(imp(100, 200)).build(),
                FakeAws.asg("web-asg").build());
        SpotGroup g = row(f, GroupState.ACTIVE);
        g.setDesiredConfig(DESIRED_OWNED);
        when(f.groupLookup.live(eq(Provider.AWS), eq(7L), anyString())).thenReturn(Optional.of(g));
        PlanBody body = f.provider.createPlan(new PlanRequest(new GroupRef(Provider.AWS, 7L,
                FakeAws.REGION + "/web-asg", null), OperationKind.REVERT, null), f.aws.view());
        JsonNode diff = json(body.diffJson());
        JsonNode row = null;
        for (JsonNode d : diff) {
            if ("instanceMaintenancePolicy".equals(d.path("field").asText())) {
                row = d;
            }
        }
        assertThat(row).isNotNull();
        assertThat(row.path("from").asText()).isEqualTo("min 100 / max 200");
        assertThat(row.path("to").asText()).isEqualTo("none");
    }

    // ---- the program ----

    @Test
    void drainPrecedesRestoreAndOnlyTheThreeWritingStepsMutate() {
        AwsConversionFixture f = fixture(converted(), FakeAws.asg("web-asg").build());
        StepProgram p = f.provider.restore(revert(f, "{\"mode\":\"RESTORE\"}"), null);
        assertThat(names(p)).containsExactly("VERIFY_PRECONDITIONS", "DRAIN_SPOT", "WAIT_SPOT_DRAINED", "RESTORE_SHAPE",
                "VERIFY_REVERTED", "REMOVE_MARKER");
        assertThat(p.steps().stream().filter(Step::mutates).map(Step::name).toList())
                .containsExactly("DRAIN_SPOT", "RESTORE_SHAPE", "REMOVE_MARKER");
    }

    @Test
    void theWholeRevertRunsInOrderAndRestoresTheOriginalShape() throws IOException {
        AwsConversionFixture f = fixture(converted(), FakeAws.asg("web-asg").build());
        f.spotInstances.add("i-1");
        f.spotInstances.add("i-2");
        StepProgram p = f.provider.restore(revert(f, "{\"mode\":\"RESTORE\"}"), null);
        assertThat(run(p, 0, f)).isInstanceOf(StepOutcome.Done.class);
        assertThat(f.writeCalls()).as("step 0 writes nothing").isEmpty();
        assertThat(run(p, 1, f)).isInstanceOf(StepOutcome.Done.class);
        // The drain: share 100, base unchanged, no launch template, no capacity field.
        UpdateAutoScalingGroupRequest drain = f.updates.get(0);
        assertThat(drain.mixedInstancesPolicy().instancesDistribution().onDemandPercentageAboveBaseCapacity()).isEqualTo(100);
        assertThat(drain.mixedInstancesPolicy().instancesDistribution().onDemandBaseCapacity()).isEqualTo(1);
        assertThat(drain.mixedInstancesPolicy().launchTemplate()).isNull();
        assertThat(AwsRestoreRequests.forbidden(drain)).isEmpty();
        // Two Spot instances remain: the wait reports it and keeps waiting, with the shape untouched.
        StepOutcome waiting = run(p, 2, f);
        assertThat(waiting).isInstanceOfSatisfying(StepOutcome.InProgress.class, w -> {
            assertThat(w.pollAfter()).isEqualTo(Duration.ofSeconds(60));
            assertThat(w.progressJson()).contains("Spot instances remaining: 2");
        });
        assertThat(f.updates).as("no restore while Spot remains").hasSize(1);
        f.spotInstances.clear();
        assertThat(run(p, 2, f)).isInstanceOf(StepOutcome.Done.class);
        assertThat(run(p, 3, f)).isInstanceOf(StepOutcome.Done.class);
        UpdateAutoScalingGroupRequest restore = f.updates.get(1);
        assertThat(restore.launchTemplate().launchTemplateId()).isEqualTo("lt-0abc");
        assertThat(restore.mixedInstancesPolicy()).isNull();
        assertThat(restore.capacityRebalance()).isFalse();
        assertThat(AwsRestoreRequests.forbidden(restore)).isEmpty();
        assertThat(run(p, 4, f)).isInstanceOf(StepOutcome.Done.class);
        assertThat(run(p, 5, f)).isInstanceOf(StepOutcome.Done.class);
        assertThat(f.log).containsExactly("update", "update", "deleteTags");
        assertThat(f.group().mixedInstancesPolicy()).isNull();
        assertThat(f.group().tags()).noneMatch(t -> t.key().equals(AwsGroupFacts.MARKER_TAG));
        assertThat(f.group().desiredCapacity()).as("capacity is never written").isEqualTo(4);
        f.aws.assertReadOnly();
    }

    @Test
    void aSnapshotThatHadSpotLeavesOutTheDrainAndRestoresTheOriginalDistribution() {
        AwsConversionFixture f = fixture(converted(), converted(FakeAws.asg("web-asg")).toBuilder()
                .mixedInstancesPolicy(converted().mixedInstancesPolicy().toBuilder().instancesDistribution(
                        converted().mixedInstancesPolicy().instancesDistribution().toBuilder()
                                .onDemandPercentageAboveBaseCapacity(30).build()).build()).build());
        StepProgram p = f.provider.restore(revert(f, "{\"mode\":\"RESTORE\"}"), null);
        assertThat(names(p)).containsExactly("VERIFY_PRECONDITIONS", "RESTORE_SHAPE", "VERIFY_REVERTED", "REMOVE_MARKER");
        run(p, 0, f);
        run(p, 1, f);
        assertThat(f.updates).hasSize(1);
        assertThat(f.updates.get(0).mixedInstancesPolicy().instancesDistribution().onDemandPercentageAboveBaseCapacity())
                .isEqualTo(30);
    }

    @Test
    void aDesiredOneGroupIsRevertedWithoutAnyEligibilityOrSizeCheck() {
        AutoScalingGroup tiny = converted(FakeAws.asg("web-asg").minSize(1).desiredCapacity(1)
                .instances(FakeAws.instance("i-1", false)));
        AwsConversionFixture f = fixture(tiny, FakeAws.asg("web-asg").build());
        PlanBody body = plan(f, GroupState.ACTIVE);
        assertThat(body.eligibility().blockers()).isEmpty();
        StepProgram p = f.provider.restore(revert(f, "{\"mode\":\"RESTORE\"}"), null);
        for (int i = 0; i < p.size(); i++) {
            assertThat(run(p, i, f)).as(p.at(i).name()).isInstanceOf(StepOutcome.Done.class);
        }
        assertThat(f.group().mixedInstancesPolicy()).isNull();
        assertThat(f.group().desiredCapacity()).isEqualTo(1);
    }

    // ---- the plan, the permission pre-check, drain-only (AC-REV-05) ----

    @Test
    void aRoleWithoutEc2PassRoleShowsTheGapInThePlanAndOffersDrainOnly() throws IOException {
        AwsConversionFixture f = fixture(converted(), FakeAws.asg("web-asg").build());
        instanceProfile(f, true);
        PlanBody body = plan(f, GroupState.ACTIVE);
        assertThat(body.eligibility().blockers()).isEmpty();
        assertThat(body.eligibility().warnings()).extracting(w -> w.id()).containsExactly("REVERT_NEEDS_PASSROLE");
        JsonNode target = json(body.targetJson());
        assertThat(target.path("mode").asText()).isEqualTo("DRAIN_ONLY");
        assertThat(target.path("exact").asBoolean()).isFalse();
        assertThat(target.path("reasons")).extracting(JsonNode::asText).containsExactly("PASSROLE_DENIED");
        assertThat(target.path("instancesDistribution").path("onDemandPercentageAboveBaseCapacity").asInt()).isEqualTo(100);
        assertThat(target.path("instancesDistribution").path("onDemandBaseCapacity").asInt()).as("B unchanged").isEqualTo(1);
        JsonNode path = json(body.revertPathJson());
        assertThat(path.path("method").asText()).isEqualTo("DRAIN_ONLY");
        assertThat(path.path("exact").asBoolean()).isFalse();
        assertThat(path.path("remediation").asText()).isEqualTo("Update stack");
    }

    @Test
    void theDrainOnlyRevertEndsRevertedNeverFailedAndKeepsThePolicy() throws IOException {
        AwsConversionFixture f = fixture(converted(), FakeAws.asg("web-asg").build());
        instanceProfile(f, true);
        PlanBody body = plan(f, GroupState.ACTIVE);
        f.spotInstances.add("i-1");
        StepProgram p = f.provider.restore(revert(f, body.targetJson()), null);
        assertThat(names(p)).containsExactly("VERIFY_PRECONDITIONS", "DRAIN_SPOT", "WAIT_SPOT_DRAINED",
                "VERIFY_REVERTED", "REMOVE_MARKER");
        assertThat(run(p, 0, f)).isInstanceOf(StepOutcome.Done.class);
        assertThat(run(p, 1, f)).isInstanceOf(StepOutcome.Done.class);
        f.spotInstances.clear();
        assertThat(run(p, 2, f)).isInstanceOf(StepOutcome.Done.class);
        StepOutcome verified = run(p, 3, f);
        assertThat(verified).isInstanceOfSatisfying(StepOutcome.Done.class, d -> assertThat(d.outputJson())
                .contains("DR-3").contains("PASSROLE_DENIED").contains("\"exact\":false"));
        assertThat(run(p, 4, f)).isInstanceOf(StepOutcome.Done.class);
        assertThat(f.group().mixedInstancesPolicy()).as("the policy stays").isNotNull();
        assertThat(f.group().mixedInstancesPolicy().instancesDistribution().onDemandPercentageAboveBaseCapacity()).isEqualTo(100);
        assertThat(f.updates).as("one write, no launch template").hasSize(1);
        assertThat(f.updates.get(0).launchTemplate()).isNull();
    }

    @Test
    void anExactRevertWithoutPassRoleFailsInStepZeroBeforeAnythingIsDrained() {
        AwsConversionFixture f = fixture(converted(), FakeAws.asg("web-asg").build());
        instanceProfile(f, true);
        StepProgram p = f.provider.restore(revert(f, "{\"mode\":\"RESTORE\"}"), null);
        StepOutcome out = run(p, 0, f);
        assertThat(out).isInstanceOfSatisfying(StepOutcome.Failed.class, x ->
                assertThat(x.code()).isEqualTo("REVERT_NEEDS_PASSROLE"));
        assertThat(f.writeCalls()).as("nothing was drained").isEmpty();
    }

    @Test
    void aDeniedSimulateIsCheckFailedInThePlanAndStillOffersDrainOnly() throws IOException {
        AwsConversionFixture f = fixture(converted(), FakeAws.asg("web-asg").build());
        doThrow(AwsServiceException.builder().statusCode(403).awsErrorDetails(AwsErrorDetails.builder()
                .errorCode("AccessDenied").build()).build()).when(f.aws.iam)
                .simulatePrincipalPolicy(any(SimulatePrincipalPolicyRequest.class));
        PlanBody body = plan(f, GroupState.ACTIVE);
        assertThat(body.eligibility().warnings()).extracting(w -> w.id()).containsExactly("REVERT_CHECK_FAILED");
        assertThat(body.eligibility().warnings().get(0).code()).isEqualTo("CHECK_FAILED");
        assertThat(json(body.targetJson()).path("mode").asText()).isEqualTo("DRAIN_ONLY");
        assertThat(json(body.revertPathJson()).path("remediation").asText()).isEqualTo("Update stack");
    }

    @Test
    void anUpdatePermissionGapIsABlockerOfThePlanAndStepZeroFailsBeforeAnyWrite() {
        AwsConversionFixture f = fixture(converted(), FakeAws.asg("web-asg").build());
        doAnswer(inv -> {
            SimulatePrincipalPolicyRequest r = inv.getArgument(0);
            return SimulatePrincipalPolicyResponse.builder().evaluationResults(r.actionNames().stream()
                    .map(a -> EvaluationResult.builder().evalActionName(a).evalDecision("implicitDeny").build()).toList())
                    .build();
        }).when(f.aws.iam).simulatePrincipalPolicy(any(SimulatePrincipalPolicyRequest.class));
        assertThat(plan(f, GroupState.ACTIVE).eligibility().blockers()).extracting(b -> b.code())
                .containsExactly("PERMISSION_MISSING");
        StepProgram p = f.provider.restore(revert(f, "{\"mode\":\"RESTORE\"}"), null);
        assertThat(run(p, 0, f)).isInstanceOfSatisfying(StepOutcome.Failed.class, x ->
                assertThat(x.code()).isEqualTo("PERMISSION_MISSING"));
        assertThat(f.writeCalls()).isEmpty();
    }

    @Test
    void aStaleGroupFailsStepZeroWithPlanStaleAndWritesNothing() {
        AwsConversionFixture f = fixture(converted(), FakeAws.asg("web-asg").build());
        when(f.facts.planLiveFingerprint(f.planId)).thenReturn(Optional.of("not-the-live-fingerprint"));
        StepProgram p = f.provider.restore(revert(f, "{\"mode\":\"RESTORE\"}"), null);
        assertThat(run(p, 0, f)).isInstanceOfSatisfying(StepOutcome.Failed.class, x ->
                assertThat(x.code()).isEqualTo("PLAN_STALE"));
        assertThat(f.writeCalls()).isEmpty();
    }

    @Test
    void aForeignInstanceRefreshWaitsInsteadOfFailing() {
        AwsConversionFixture f = fixture(converted(), FakeAws.asg("web-asg").build());
        doReturn(software.amazon.awssdk.services.autoscaling.model.DescribeInstanceRefreshesResponse.builder()
                .instanceRefreshes(software.amazon.awssdk.services.autoscaling.model.InstanceRefresh.builder()
                        .status("InProgress").build()).build()).when(f.aws.asg).describeInstanceRefreshes(
                any(software.amazon.awssdk.services.autoscaling.model.DescribeInstanceRefreshesRequest.class));
        StepProgram p = f.provider.restore(revert(f, "{\"mode\":\"RESTORE\"}"), null);
        assertThat(run(p, 0, f)).isInstanceOfSatisfying(StepOutcome.Failed.class, x -> {
            assertThat(x.code()).isEqualTo("FOREIGN_CHANGE");
            assertThat(x.kind()).isEqualTo(com.xammer.cloud.service.autospotting.spi.ErrorKind.CONFLICT);
        });
    }

    @Test
    void aMissingSnapshotFailsBeforeAnyChange() {
        AwsConversionFixture f = fixture(converted(), FakeAws.asg("web-asg").build());
        f.snapshots.linked = null;
        StepProgram p = f.provider.restore(revert(f, "{\"mode\":\"RESTORE\"}"), null);
        assertThat(names(p)).containsExactly("SNAPSHOT_MISSING");
        assertThat(run(p, 0, f)).isInstanceOfSatisfying(StepOutcome.Failed.class, x ->
                assertThat(x.code()).isEqualTo("SNAPSHOT_MISSING"));
        assertThat(f.writeCalls()).isEmpty();
    }

    // ---- GR-02: the exact revert after a drain-only revert ----

    /** After the drain-only revert: XamOps' policy is still there at 100% On-Demand and the marker is gone. */
    private static AutoScalingGroup drainedGroup() {
        return converted().toBuilder().tags(java.util.List.<software.amazon.awssdk.services.autoscaling.model.TagDescription>of()).mixedInstancesPolicy(
                converted().mixedInstancesPolicy().toBuilder().instancesDistribution(
                        converted().mixedInstancesPolicy().instancesDistribution().toBuilder()
                                .onDemandPercentageAboveBaseCapacity(100).build()).build()).build();
    }

    @Test
    void share100WithSpotRunningIsBlockedUntilTheSpotInstancesAreGone() throws IOException {
        AwsConversionFixture f = fixture(drainedGroup(), FakeAws.asg("web-asg").build());
        f.spotInstances.add("i-1");
        PlanBody stuck = plan(f, GroupState.REVERTED);
        assertThat(stuck.eligibility().blockers()).extracting(c -> c.code()).containsExactly("REVERT_SPOT_STUCK");
        assertThat(stuck.eligibility().blockers().get(0).text()).contains("still running at 100% On-Demand")
                .contains("then plan again");
        f.spotInstances.clear();
        PlanBody clear = plan(f, GroupState.REVERTED);
        assertThat(clear.eligibility().blockers()).isEmpty();
        assertThat(json(clear.targetJson()).path("mode").asText()).isEqualTo("RESTORE_ONLY");
    }
    @Test
    void anUnchangedDrainStartsNoReplacementInTheFake() {
        AwsConversionFixture f = fixture(drainedGroup(), FakeAws.asg("web-asg").build());
        f.client.updateAutoScalingGroup(AwsRestoreRequests.drain(f.group()));
        assertThat(f.replacementStarted).as("re-sending share 100 changes nothing").isFalse();
        AwsConversionFixture g = fixture(converted(), FakeAws.asg("web-asg").build());
        g.client.updateAutoScalingGroup(AwsRestoreRequests.drain(g.group()));
        assertThat(g.replacementStarted).as("50 -> 100 changes the purchase mix").isTrue();
    }
    @Test
    void theDrainProbeDoesNotAdoptAShare100GroupThatStillRunsSpot() throws IOException {
        AwsConversionFixture f = fixture(drainedGroup(), FakeAws.asg("web-asg").build());
        PlanBody body = plan(f, GroupState.REVERTED);
        StepProgram p = f.provider.restore(revert(f, body.targetJson()), null);
        f.spotInstances.add("i-1");
        assertThat(p.at(1).probe(f.stepContext(1))).as("share 100 with Spot running is not drained")
                .isNotInstanceOf(StepOutcome.Done.class);
        f.spotInstances.clear();
        assertThat(p.at(1).probe(f.stepContext(1))).isInstanceOf(StepOutcome.Done.class);
    }
    @Test
    void anUnreadableSpotCountDoesNotFailTheDrainStepAndTheDrainStaysOpen() throws IOException {
        AwsConversionFixture f = fixture(drainedGroup(), FakeAws.asg("web-asg").build());
        PlanBody body = plan(f, GroupState.REVERTED);
        StepProgram p = f.provider.restore(revert(f, body.targetJson()), null);
        doThrow(AwsServiceException.builder().statusCode(500).build()).when(f.aws.asg).describeScalingActivities(
                any(software.amazon.awssdk.services.autoscaling.model.DescribeScalingActivitiesRequest.class));
        assertThat(p.at(1).probe(f.stepContext(1))).as("an unknown count is not drained")
                .isNotInstanceOf(StepOutcome.Done.class);
        assertThat(p.at(1).execute(f.stepContext(1))).as("the step sends the drain instead of failing")
                .isInstanceOf(StepOutcome.Done.class);
        assertThat(f.updates).hasSize(1);
    }
    @Test
    void gr02RunsStepZeroThenRestoreWithNoDrainCall() throws IOException {
        AwsConversionFixture f = fixture(drainedGroup(), FakeAws.asg("web-asg").build());
        PlanBody body = plan(f, GroupState.REVERTED);
        assertThat(json(body.targetJson()).path("mode").asText()).isEqualTo("RESTORE_ONLY");
        assertThat(json(body.targetJson()).path("exact").asBoolean()).isTrue();
        StepProgram p = f.provider.restore(revert(f, body.targetJson()), null);
        // RESTORE_ONLY keeps the drain steps (revert matrix B1/B5); on a drained group they make no call.
        assertThat(names(p)).containsExactly("VERIFY_PRECONDITIONS", "DRAIN_SPOT", "WAIT_SPOT_DRAINED", "RESTORE_SHAPE",
                "VERIFY_REVERTED", "REMOVE_MARKER");
        for (int i = 0; i < p.size(); i++) {
            assertThat(run(p, i, f)).as(p.at(i).name()).isInstanceOf(StepOutcome.Done.class);
        }
        assertThat(f.updates).as("only the restore, no drain").hasSize(1);
        assertThat(f.updates.get(0).launchTemplate()).isNotNull();
        assertThat(f.group().mixedInstancesPolicy()).isNull();
    }

    @Test
    void gr02WhilePassRoleIsStillDeniedIsABlockedPlanNotAnotherDrain() throws IOException {
        AwsConversionFixture f = fixture(drainedGroup(), FakeAws.asg("web-asg").build());
        instanceProfile(f, true);
        PlanBody body = plan(f, GroupState.REVERTED);
        assertThat(body.eligibility().blockers()).extracting(b -> b.id()).containsExactly("REVERT_NEEDS_PASSROLE");
    }

    /** Revert matrix Q-B: with the drain still open (share 50) a permission gap gives another drain, not a blocker. */
    @Test
    void gr02WithTheDrainStillOpenAndPassRoleDeniedDrainsAgain() throws IOException {
        AwsConversionFixture f = fixture(converted(), FakeAws.asg("web-asg").build());
        instanceProfile(f, true);
        PlanBody body = plan(f, GroupState.REVERTED);
        assertThat(body.eligibility().blockers()).isEmpty();
        assertThat(json(body.targetJson()).path("mode").asText()).isEqualTo("DRAIN_ONLY");
    }

    // ---- exact=false and confirmation ----

    @Test
    void aDeletedTemplateVersionMakesTheRestoreInexactAndNeedsAnAckThatStepZeroEnforces() throws IOException {
        // The snapshot pinned version 4 of the template; that version was deleted. The live $Default still exists.
        AutoScalingGroup pinned = FakeAws.asg("web-asg").launchTemplate(LaunchTemplateSpecification.builder()
                .launchTemplateId("lt-0abc").version("4").build()).build();
        AwsConversionFixture f = fixture(converted(), pinned);
        DescribeLaunchTemplateVersionsResponse exists = f.aws.ec2
                .describeLaunchTemplateVersions(DescribeLaunchTemplateVersionsRequest.builder().versions("$Default").build());
        doAnswer(inv -> {
            DescribeLaunchTemplateVersionsRequest r = inv.getArgument(0);
            if (r.versions().contains("4")) {
                throw AwsServiceException.builder().statusCode(400).awsErrorDetails(AwsErrorDetails.builder()
                        .errorCode("InvalidLaunchTemplateId.VersionNotFound").build()).build();
            }
            return exists;
        }).when(f.aws.ec2).describeLaunchTemplateVersions(any(DescribeLaunchTemplateVersionsRequest.class));
        PlanBody body = plan(f, GroupState.ACTIVE);
        assertThat(body.eligibility().warnings()).extracting(w -> w.id()).contains("TEMPLATE_VERSION_DELETED");
        assertThat(json(body.targetJson()).path("exact").asBoolean()).isFalse();
        assertThat(json(body.targetJson()).path("launchTemplate").path("version").asText()).isEqualTo("$Default");
        StepProgram p = f.provider.restore(revert(f, body.targetJson()), null);
        assertThat(run(p, 0, f)).as("the ack was not given").isInstanceOfSatisfying(StepOutcome.Failed.class, x ->
                assertThat(x.code()).isEqualTo("ACK_REQUIRED"));
        when(f.facts.planConfirmAcks(f.planId)).thenReturn(Optional.of("[\"TEMPLATE_VERSION_DELETED\"]"));
        assertThat(run(p, 0, f)).isInstanceOf(StepOutcome.Done.class);
    }

    @Test
    void ifAwsRefusesTheUnwrapTheSingleOverrideFallbackIsUsedAndReportedInexact() {
        AwsConversionFixture f = fixture(drainedGroup(), FakeAws.asg("web-asg").build());
        doAnswer(inv -> {
            UpdateAutoScalingGroupRequest r = inv.getArgument(0);
            if (r.launchTemplate() != null && r.mixedInstancesPolicy() == null) {
                throw AwsServiceException.builder().statusCode(400).awsErrorDetails(AwsErrorDetails.builder()
                        .errorCode("ValidationError").build()).build();
            }
            f.log.add("update");
            f.updates.add(r);
            f.applyUpdate(r);
            return UpdateAutoScalingGroupResponse.builder().build();
        }).when(f.write).updateAutoScalingGroup(any(UpdateAutoScalingGroupRequest.class));
        StepProgram p = f.provider.restore(revert(f, "{\"mode\":\"RESTORE_ONLY\"}"), null);
        // Steps 0-2: preconditions and the no-op drain of an already drained group; 3 is the restore.
        for (int i = 0; i < 3; i++) {
            assertThat(run(p, i, f)).as(p.at(i).name()).isInstanceOf(StepOutcome.Done.class);
        }
        assertThat(f.updates).as("no drain call").isEmpty();
        StepOutcome restored = run(p, 3, f);
        assertThat(restored).isInstanceOfSatisfying(StepOutcome.Done.class, d ->
                assertThat(d.outputJson()).contains("UNWRAP_UNSUPPORTED").contains("\"exact\":false"));
        UpdateAutoScalingGroupRequest fallback = f.updates.get(0);
        assertThat(fallback.mixedInstancesPolicy().launchTemplate().overrides()).extracting(o -> o.instanceType())
                .containsExactly("m5.large");
        assertThat(fallback.mixedInstancesPolicy().instancesDistribution().onDemandBaseCapacity()).isZero();
        assertThat(fallback.mixedInstancesPolicy().instancesDistribution().onDemandPercentageAboveBaseCapacity()).isEqualTo(100);
        assertThat(fallback.capacityRebalance()).isFalse();
        assertThat(run(p, 4, f)).isInstanceOfSatisfying(StepOutcome.Done.class, d ->
                assertThat(d.outputJson()).contains("UNWRAP_UNSUPPORTED"));
    }

    // ---- request goldens (AC-REV-01) ----

    @Test
    void theDrainRequestMatchesTheGolden() throws IOException {
        assertThat(SdkJson.of(AwsRestoreRequests.drain(converted()))).isEqualTo(golden("revert-drain"));
    }

    @Test
    void theStandaloneRestoreRequestMatchesTheGolden() throws IOException {
        AwsConversionFixture f = fixture(converted(), FakeAws.asg("web-asg").build());
        AwsThreeWayDiff.Result r = AwsThreeWayDiff.compute(AwsSnapshotCodec.decodeOwned(
                f.snapshots.linked.payload()).orElseThrow(), json(DESIRED), converted(), null, false);
        assertThat(SdkJson.of(AwsRestoreRequests.restore("web-asg", r.target()))).isEqualTo(golden("revert-restore-standalone"));
    }

    @Test
    void thePolicyRestoreRequestMatchesTheGolden() throws IOException {
        AutoScalingGroup policy = converted().toBuilder().mixedInstancesPolicy(converted().mixedInstancesPolicy().toBuilder()
                .launchTemplate(LaunchTemplate.builder().launchTemplateSpecification(LaunchTemplateSpecification.builder()
                        .launchTemplateId("lt-0abc").version("$Default").build()).overrides(
                                LaunchTemplateOverrides.builder().instanceType("m5.large").weightedCapacity("1").build(),
                                LaunchTemplateOverrides.builder().instanceType("m6i.large").weightedCapacity("1").build())
                        .build())
                .instancesDistribution(InstancesDistribution.builder().onDemandAllocationStrategy("prioritized")
                        .onDemandBaseCapacity(2).onDemandPercentageAboveBaseCapacity(0)
                        .spotAllocationStrategy("capacity-optimized-prioritized").build()).build()).capacityRebalance(false).build();
        AwsThreeWayDiff.Result r = AwsThreeWayDiff.compute(AwsSnapshotCodec.decodeOwned(AwsSnapshotCodec.encode(
                FakeAws.REGION, policy, AwsSnapshotCodec.Context.none(), false).payloadJson()).orElseThrow(),
                json(DESIRED), weighted(converted()), null, false);
        assertThat(SdkJson.of(AwsRestoreRequests.restore("web-asg", r.target()))).isEqualTo(golden("revert-restore-policy"));
    }

    // ---- dark ----

    @Test
    void theRestoreProgramIsRefusedWhileTheGateIsClosed() {
        AwsConversionFixture f = new AwsConversionFixture(converted(), false);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> f.provider.restore(
                new OperationContext(f.opId, OperationKind.REVERT, row(f, GroupState.REVERTING), null, null), null))
                .isInstanceOf(AutoSpottingApiException.class).hasMessageContaining("not ready");
    }

    @Test
    void everyStepDescribesItsIntentWithoutTouchingAws() {
        AwsConversionFixture f = fixture(converted(), FakeAws.asg("web-asg").build());
        StepProgram p = f.provider.restore(revert(f, "{\"mode\":\"RESTORE\"}"), null);
        int before = Mockito.mockingDetails(f.aws.asg).getInvocations().size();
        for (int i = 0; i < p.size(); i++) {
            assertThat(p.at(i).describeIntent(f.stepContext(i))).isNotBlank();
        }
        assertThat(Mockito.mockingDetails(f.aws.asg).getInvocations()).hasSize(before);
        assertThat(f.writeCalls()).isEmpty();
    }
}
