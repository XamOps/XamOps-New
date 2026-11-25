package com.xammer.cloud.service.autospotting.aws;

import com.fasterxml.jackson.databind.JsonNode;
import com.xammer.cloud.domain.autospotting.OperationKind;
import com.xammer.cloud.service.autospotting.core.AutoSpottingApiException;
import com.xammer.cloud.service.autospotting.spi.ErrorKind;
import com.xammer.cloud.service.autospotting.spi.Step;
import com.xammer.cloud.service.autospotting.spi.StepContext;
import com.xammer.cloud.service.autospotting.spi.StepOutcome;
import com.xammer.cloud.service.autospotting.spi.StepProgram;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;
import software.amazon.awssdk.services.autoscaling.model.CreateOrUpdateTagsRequest;
import software.amazon.awssdk.services.autoscaling.model.DescribeInstanceRefreshesRequest;
import software.amazon.awssdk.services.autoscaling.model.DescribeInstanceRefreshesResponse;
import software.amazon.awssdk.services.autoscaling.model.InstanceRefresh;
import software.amazon.awssdk.services.autoscaling.model.LaunchTemplateSpecification;
import software.amazon.awssdk.services.iam.model.EvaluationResult;
import software.amazon.awssdk.services.iam.model.SimulatePrincipalPolicyRequest;
import software.amazon.awssdk.services.iam.model.SimulatePrincipalPolicyResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * The AWS conversion programs over a stubbed SDK (plan-phase-18.md task 18.1; AC-AWS-01, -02, -04,
 * -06, -07). No test makes a real AWS call. The write client is a separate mock, so every assertion
 * about "no mutating call" reads that mock's recorded calls.
 */
class AwsStepLibraryTest {

    private static AwsConversionFixture enableFixture() {
        return new AwsConversionFixture(FakeAws.asg("web-asg").build(), true);
    }

    private static List<String> names(StepProgram p) {
        return p.steps().stream().map(Step::name).toList();
    }

    private static StepOutcome run(Step step, AwsConversionFixture f, int seq) {
        return step.execute(f.stepContext(seq));
    }

    /** Run every step of {@code program} until one is not Done; returns the last outcome. */
    private static StepOutcome runAll(StepProgram program, AwsConversionFixture f) {
        StepOutcome last = null;
        for (int i = 0; i < program.size(); i++) {
            last = run(program.at(i), f, i);
            if (!(last instanceof StepOutcome.Done)) {
                return last;
            }
        }
        return last;
    }

    private static void denyUpdate(AwsConversionFixture f) {
        doAnswer(inv -> {
            SimulatePrincipalPolicyRequest r = inv.getArgument(0);
            return SimulatePrincipalPolicyResponse.builder().evaluationResults(r.actionNames().stream()
                    .map(a -> EvaluationResult.builder().evalActionName(a)
                            .evalDecision(a.equals("autoscaling:UpdateAutoScalingGroup") ? "implicitDeny" : "allowed")
                            .build()).toList()).build();
        }).when(f.aws.iam).simulatePrincipalPolicy(any(SimulatePrincipalPolicyRequest.class));
    }

    // ---- the program shape ----

    @Test
    void enableAndAdoptRunAllSevenStepsInOrder() throws IOException {
        AwsConversionFixture f = enableFixture();
        String target = f.plan("conservative").targetJson();
        for (OperationKind kind : List.of(OperationKind.ENABLE, OperationKind.ADOPT)) {
            assertThat(names(f.library.program(f.context(kind, target, null)))).containsExactly(
                    "VERIFY_PRECONDITIONS", "CHECK_PERMISSIONS", "PERSIST_SNAPSHOT", "UPDATE_DISTRIBUTION",
                    "WAIT_ROLLOUT", "VERIFY_TARGET", "WRITE_MARKER");
        }
    }

    @Test
    void configApplyAndProfileChangeSkipTheSnapshotAndTheMarker() throws IOException {
        AwsConversionFixture f = enableFixture();
        String target = f.plan("balanced").targetJson();
        for (OperationKind kind : List.of(OperationKind.CONFIG_APPLY, OperationKind.PROFILE_CHANGE)) {
            assertThat(names(f.library.program(f.context(kind, target, null)))).containsExactly(
                    "VERIFY_PRECONDITIONS", "CHECK_PERMISSIONS", "UPDATE_DISTRIBUTION", "WAIT_ROLLOUT", "VERIFY_TARGET");
        }
    }

    @Test
    void onlyTheUpdateAndTheMarkerMutate() throws IOException {
        AwsConversionFixture f = enableFixture();
        StepProgram p = f.library.program(f.context(OperationKind.ENABLE, f.plan("conservative").targetJson(), null));
        assertThat(p.steps().stream().filter(Step::mutates).map(Step::name).toList())
                .containsExactly("UPDATE_DISTRIBUTION", "WRITE_MARKER");
    }

    @Test
    void theWriteClientHasNoInstanceRefreshMethod() {
        List<String> methods = java.util.Arrays.stream(AwsMutatingClient.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName).toList();
        assertThat(methods).noneMatch(m -> m.toLowerCase(java.util.Locale.ROOT).contains("refresh"));
        assertThat(methods).contains("updateAutoScalingGroup", "createOrUpdateTags", "deleteTags");
        assertThat(methods).doesNotContain("setDesiredCapacity", "terminateInstanceInAutoScalingGroup");
    }

    @Test
    void everyStepDescribesItsIntentWithoutTouchingAws() throws IOException {
        AwsConversionFixture f = enableFixture();
        StepProgram p = f.library.program(f.context(OperationKind.ENABLE, f.plan("conservative").targetJson(), null));
        int readsBefore = Mockito.mockingDetails(f.aws.asg).getInvocations().size();
        for (int i = 0; i < p.size(); i++) {
            assertThat(p.at(i).describeIntent(f.stepContext(i))).isNotBlank();
        }
        assertThat(Mockito.mockingDetails(f.aws.asg).getInvocations()).hasSize(readsBefore);
        assertThat(f.writeCalls()).isEmpty();
    }

    // ---- the happy path and its ordering (AC-AWS-04) ----

    @Test
    void snapshotIsPersistedBeforeTheFirstMutationAndTheMarkerIsLast() throws IOException {
        AwsConversionFixture f = enableFixture();
        String target = f.plan("conservative").targetJson();
        f.spotInstances.add("i-1"); // Conservative at desired 4 expects one Spot instance
        StepProgram p = f.library.program(f.context(OperationKind.ENABLE, target, null));

        StepOutcome last = runAll(p, f);

        assertThat(last).isInstanceOf(StepOutcome.Done.class);
        assertThat(f.log).containsExactly("snapshot", "update", "tags");
        assertThat(f.writeCalls()).containsExactly("updateAutoScalingGroup", "createOrUpdateTags");
        AutoScalingGroup after = f.group();
        assertThat(after.mixedInstancesPolicy()).isNotNull();
        assertThat(after.capacityRebalance()).isTrue();
        assertThat(after.desiredCapacity()).isEqualTo(4); // untouched
        assertThat(after.tags()).anyMatch(t -> t.key().equals("xamops:autospotting:group-id")
                && t.value().equals(f.groupId.toString()) && Boolean.FALSE.equals(t.propagateAtLaunch()));
        // The snapshot is of the ORIGINAL (launch-template) group.
        JsonNode snapshot = f.json(f.snapshots.stored.payload());
        assertThat(snapshot.path("owned").path("shape").asText()).isEqualTo("LAUNCH_TEMPLATE");
    }

    @Test
    void theMarkerIsNotWrittenWhileTheRolloutIsStillWaiting() throws IOException {
        AwsConversionFixture f = enableFixture();
        String target = f.plan("conservative").targetJson();
        // No Spot instance yet, so the profile's one Spot instance is missing.
        StepProgram p = f.library.program(f.context(OperationKind.ENABLE, target, null));

        StepOutcome last = runAll(p, f);

        assertThat(last).isInstanceOf(StepOutcome.InProgress.class);
        assertThat(f.log).containsExactly("snapshot", "update");
        assertThat(f.group().tags()).noneMatch(t -> t.key().equals("xamops:autospotting:group-id"));
    }

    @Test
    void aRetryAfterACrashMakesNoSecondUpdate() throws IOException {
        AwsConversionFixture f = enableFixture();
        String target = f.plan("conservative").targetJson();
        f.spotInstances.add("i-1");
        StepProgram p = f.library.program(f.context(OperationKind.ENABLE, target, null));
        runAll(p, f);

        // A crash after the update but before its step row was written: the resumer re-runs from the
        // update step (steps 0-2 are recorded DONE and are not repeated). Every step is convergent.
        StepOutcome again = null;
        for (int i = 3; i < p.size(); i++) {
            again = run(p.at(i), f, i);
        }

        assertThat(again).isInstanceOf(StepOutcome.Done.class);
        assertThat(f.updates).hasSize(1);
        assertThat(f.writeCalls()).containsExactly("updateAutoScalingGroup", "createOrUpdateTags");
        assertThat(f.snapshots.stored).isNotNull();
        assertThat(f.log.stream().filter("snapshot"::equals)).hasSize(1);
    }

    @Test
    void probeOfTheUpdateStepIsDoneOnlyOnceTheGroupMatches() throws IOException {
        AwsConversionFixture f = enableFixture();
        String target = f.plan("conservative").targetJson();
        StepProgram p = f.library.program(f.context(OperationKind.ENABLE, target, null));
        Step update = p.at(3);
        assertThat(update.probe(f.stepContext(3))).isNotInstanceOf(StepOutcome.Done.class);
        run(update, f, 3);
        assertThat(update.probe(f.stepContext(3))).isInstanceOf(StepOutcome.Done.class);
    }

    // ---- pre-mutation failures: zero mutating calls ----

    @Test
    void aDeniedSimulateFailsWithZeroMutatingCalls() throws IOException {
        AwsConversionFixture f = enableFixture();
        String target = f.plan("conservative").targetJson();
        denyUpdate(f);
        StepProgram p = f.library.program(f.context(OperationKind.ENABLE, target, null));

        // Step 1 on its own names the action list and the PERMISSION kind.
        StepOutcome perm = run(p.at(1), f, 1);
        assertThat(perm).isInstanceOfSatisfying(StepOutcome.Failed.class, x -> {
            assertThat(x.kind()).isEqualTo(ErrorKind.PERMISSION);
            assertThat(x.code()).isEqualTo("PERMISSION_MISSING");
            assertThat(x.redactedMessage()).contains("autoscaling:UpdateAutoScalingGroup");
        });
        // The whole program stops before any write.
        StepOutcome overall = runAll(p, f);
        assertThat(overall).isInstanceOf(StepOutcome.Failed.class);
        assertThat(f.writeCalls()).isEmpty();
        assertThat(f.log).isEmpty();
    }

    @Test
    void aMissingPassRoleFailsBeforeAnyChange() throws IOException {
        AwsConversionFixture f = enableFixture();
        // Stub the template's instance profile before anything reads the template (the reader caches it).
        doAnswer(inv -> {
            SimulatePrincipalPolicyRequest r = inv.getArgument(0);
            return SimulatePrincipalPolicyResponse.builder().evaluationResults(r.actionNames().stream()
                    .map(a -> EvaluationResult.builder().evalActionName(a)
                            .evalDecision(a.equals("iam:PassRole") ? "implicitDeny" : "allowed").build()).toList())
                    .build();
        }).when(f.aws.iam).simulatePrincipalPolicy(any(SimulatePrincipalPolicyRequest.class));
        // An instance profile on the template is what makes PassRole relevant.
        doReturn(software.amazon.awssdk.services.ec2.model.DescribeLaunchTemplateVersionsResponse.builder()
                .launchTemplateVersions(software.amazon.awssdk.services.ec2.model.LaunchTemplateVersion.builder()
                        .versionNumber(7L).launchTemplateData(
                                software.amazon.awssdk.services.ec2.model.ResponseLaunchTemplateData.builder()
                                        .imageId("ami-0aaa").instanceType("m5.large")
                                        .iamInstanceProfile(software.amazon.awssdk.services.ec2.model
                                                .LaunchTemplateIamInstanceProfileSpecification.builder()
                                                .name("web-profile").build()).build()).build()).build())
                .when(f.aws.ec2).describeLaunchTemplateVersions(
                        any(software.amazon.awssdk.services.ec2.model.DescribeLaunchTemplateVersionsRequest.class));
        doReturn(software.amazon.awssdk.services.iam.model.GetInstanceProfileResponse.builder()
                .instanceProfile(software.amazon.awssdk.services.iam.model.InstanceProfile.builder()
                        .instanceProfileName("web-profile").roles(software.amazon.awssdk.services.iam.model.Role
                                .builder().arn("arn:aws:iam::111122223333:role/web").build()).build()).build())
                .when(f.aws.iam).getInstanceProfile(any(software.amazon.awssdk.services.iam.model
                        .GetInstanceProfileRequest.class));
        String target = f.plan("conservative").targetJson();
        StepProgram p = f.library.program(f.context(OperationKind.ENABLE, target, null));

        StepOutcome out = run(p.at(1), f, 1);

        assertThat(out).isInstanceOfSatisfying(StepOutcome.Failed.class,
                x -> assertThat(x.redactedMessage()).contains("iam:PassRole"));
        assertThat(f.writeCalls()).isEmpty();
    }

    @Test
    void aStalePlanFailsStep0WithPlanStale() throws IOException {
        AwsConversionFixture f = enableFixture();
        String target = f.plan("conservative").targetJson();
        f.replaceGroup(FakeAws.asg("web-asg").launchTemplate(LaunchTemplateSpecification.builder()
                .launchTemplateId("lt-0different").version("$Default").build()).build());
        StepProgram p = f.library.program(f.context(OperationKind.ENABLE, target, null));

        StepOutcome out = run(p.at(0), f, 0);

        assertThat(out).isInstanceOfSatisfying(StepOutcome.Failed.class, x -> {
            assertThat(x.code()).isEqualTo("PLAN_STALE");
            assertThat(x.kind()).isEqualTo(ErrorKind.VALIDATION);
        });
        assertThat(f.writeCalls()).isEmpty();
    }

    @Test
    void aCustomerPolicyAddedAfterAFirstEnablePlanIsPlanStaleAndNeverOverwritten() throws IOException {
        AwsConversionFixture f = enableFixture();
        String target = f.plan("conservative").targetJson();
        assertThat(target).contains("\"owned\":true");
        // A plan stored without the policy (as when ownership was read from the row's old desired_config) must not pass.
        when(f.facts.planLiveFingerprint(any())).thenReturn(Optional.of(
                AwsFingerprint.of(AwsGroupFacts.from(FakeAws.REGION, f.group()))));
        f.replaceGroup(FakeAws.asg("web-asg").instanceMaintenancePolicy(
                software.amazon.awssdk.services.autoscaling.model.InstanceMaintenancePolicy.builder()
                        .minHealthyPercentage(90).maxHealthyPercentage(150).build()).build());
        StepProgram p = f.library.program(f.context(OperationKind.ENABLE, target, null));
        StepOutcome out = run(p.at(0), f, 0);
        assertThat(out).isInstanceOfSatisfying(StepOutcome.Failed.class,
                x -> assertThat(x.code()).isEqualTo("PLAN_STALE"));
        assertThat(f.writeCalls()).isEmpty();
    }
    @Test
    void configApplyAndProfileChangeCompensationKeepTheOwnedPolicy() throws IOException {
        String owned = "{\"instanceMaintenancePolicy\":{\"min\":100,\"max\":200,\"owned\":true}}";
        software.amazon.awssdk.services.autoscaling.model.InstanceMaintenancePolicy imp =
                software.amazon.awssdk.services.autoscaling.model.InstanceMaintenancePolicy.builder()
                        .minHealthyPercentage(100).maxHealthyPercentage(200).build();
        for (OperationKind kind : List.of(OperationKind.CONFIG_APPLY, OperationKind.PROFILE_CHANGE)) {
            AwsConversionFixture f = new AwsConversionFixture(FakeAws.asg("web-asg").instanceMaintenancePolicy(imp).build(), true);
            com.xammer.cloud.service.autospotting.spi.PlanBody plan = f.plan("conservative", kind, owned);
            assertThat(plan.currentJson()).contains("\"instanceMaintenancePolicy\"");
            f.spotInstances.add("i-1");
            runAll(f.library.program(f.context(kind, plan.targetJson(), null)), f);
            // The owned policy is removed before the compensation runs.
            f.replaceGroup(f.group().toBuilder().instanceMaintenancePolicy(
                    (software.amazon.awssdk.services.autoscaling.model.InstanceMaintenancePolicy) null).build());
            f.log.clear();
            StepOutcome out = runAll(f.library.program(f.compensating(kind, plan.currentJson())), f);
            assertThat(out).isInstanceOf(StepOutcome.Done.class);
            assertThat(f.group().instanceMaintenancePolicy().minHealthyPercentage()).isEqualTo(100);
            assertThat(f.group().instanceMaintenancePolicy().maxHealthyPercentage()).isEqualTo(200);
        }
    }
    @Test
    void aForeignInstanceRefreshWaitsInsteadOfFailing() throws IOException {
        AwsConversionFixture f = enableFixture();
        String target = f.plan("conservative").targetJson();
        doReturn(DescribeInstanceRefreshesResponse.builder().instanceRefreshes(
                InstanceRefresh.builder().status("InProgress").build()).build())
                .when(f.aws.asg).describeInstanceRefreshes(any(DescribeInstanceRefreshesRequest.class));
        StepProgram p = f.library.program(f.context(OperationKind.ENABLE, target, null));

        StepOutcome out = run(p.at(0), f, 0);

        assertThat(out).isInstanceOfSatisfying(StepOutcome.Failed.class, x -> {
            assertThat(x.kind()).isEqualTo(ErrorKind.CONFLICT);
            assertThat(x.code()).isEqualTo("FOREIGN_CHANGE");
        });
        assertThat(f.writeCalls()).isEmpty();
    }

    @Test
    void aLaunchConfigurationGroupIsRejectedBeforeAnyChange() throws IOException {
        AwsConversionFixture normal = enableFixture();
        String target = normal.plan("conservative").targetJson();
        AwsConversionFixture f = new AwsConversionFixture(FakeAws.asg("web-asg")
                .launchTemplate((LaunchTemplateSpecification) null).launchConfigurationName("old-lc").build(), true);
        f.plan("conservative"); // stores the plan row for this group
        StepProgram p = f.library.program(f.context(OperationKind.ENABLE, target, null));

        StepOutcome out = run(p.at(0), f, 0);

        assertThat(out).isInstanceOfSatisfying(StepOutcome.Failed.class,
                x -> assertThat(x.code()).isEqualTo("LAUNCH_CONFIGURATION"));
        assertThat(f.writeCalls()).isEmpty();
    }

    @Test
    void aGroupWithDesiredOfOneIsNeverConverted() throws IOException {
        AwsConversionFixture normal = enableFixture();
        String target = normal.plan("conservative").targetJson();
        AwsConversionFixture f = new AwsConversionFixture(FakeAws.asg("web-asg").desiredCapacity(1).minSize(1)
                .instances(FakeAws.instance("i-1", false)).build(), true);
        f.plan("conservative");
        StepProgram p = f.library.program(f.context(OperationKind.ENABLE, target, null));

        StepOutcome out = run(p.at(0), f, 0);

        assertThat(out).isInstanceOfSatisfying(StepOutcome.Failed.class,
                x -> assertThat(x.code()).isEqualTo("DESIRED_TOO_SMALL"));
        assertThat(f.writeCalls()).isEmpty();
    }

    @Test
    void aTerminateFirstMaintenancePolicyIsNeverConverted() throws IOException {
        AwsConversionFixture f = new AwsConversionFixture(FakeAws.asg("web-asg").instanceMaintenancePolicy(
                software.amazon.awssdk.services.autoscaling.model.InstanceMaintenancePolicy.builder()
                        .minHealthyPercentage(90).maxHealthyPercentage(100).build()).build(), true);
        String target = f.plan("conservative").targetJson();
        StepProgram p = f.library.program(f.context(OperationKind.ENABLE, target, null));

        StepOutcome out = run(p.at(0), f, 0);

        assertThat(out).isInstanceOfSatisfying(StepOutcome.Failed.class,
                x -> assertThat(x.code()).isEqualTo("IMP_TERMINATE_FIRST"));
        assertThat(f.writeCalls()).isEmpty();
    }

    @Test
    void aHundredPercentSpotTargetIsRefusedUnlessTheOptInWasConfirmed() throws IOException {
        AwsConversionFixture f = enableFixture();
        com.xammer.cloud.service.autospotting.spi.PlanBody plan = f.plan("full_spot");
        assertThat(plan.onDemandFloorJson()).contains("\"fullSpot\":true");
        assertThat(enableFixture().plan("conservative").onDemandFloorJson()).contains("\"fullSpot\":false");
        StepProgram p = f.library.program(f.context(OperationKind.ENABLE, plan.targetJson(), null));

        assertThat(run(p.at(0), f, 0)).isInstanceOfSatisfying(StepOutcome.Failed.class,
                x -> assertThat(x.code()).isEqualTo("FULL_SPOT_NOT_CONFIRMED"));

        when(f.facts.planConfirmAcks(f.planId)).thenReturn(Optional.of("[\"FULL_SPOT_OPTIN\"]"));
        StepOutcome confirmed = run(p.at(0), f, 0);
        assertThat(confirmed instanceof StepOutcome.Failed x && "FULL_SPOT_NOT_CONFIRMED".equals(x.code())).isFalse();
        assertThat(f.writeCalls()).isEmpty();
    }

    // ---- the gate: a closed gate reaches no mutating client ----

    @Test
    void aClosedGateNeverBuildsTheWriteClient() throws IOException {
        AwsConversionFixture f = new AwsConversionFixture(FakeAws.asg("web-asg").build(), false);
        String target = f.plan("conservative").targetJson();
        StepProgram p = f.library.program(f.context(OperationKind.ENABLE, target, null));

        assertThatThrownBy(() -> run(p.at(3), f, 3)).isInstanceOfSatisfying(AutoSpottingApiException.class,
                e -> assertThat(e.getCode()).isEqualTo("AS_PROVIDER_NOT_READY"));
        assertThat(f.writeCalls()).isEmpty();
    }

    // ---- ADOPT ----

    @Test
    void adoptUsesTheReconstructedSnapshotAndDeletesTheLegacyTag() throws IOException {
        AutoScalingGroup legacy = FakeAws.asg("web-asg").tags(FakeAws.tag("spot-enabled", "true")).build();
        AwsConversionFixture f = new AwsConversionFixture(legacy, true);
        String target = f.plan("conservative").targetJson();
        f.spotInstances.add("i-1");
        UUID reconstructed = UUID.randomUUID();
        f.snapshots.linked = new AwsSnapshotStore.Stored(reconstructed, f.groupId, false, "{}", "sha");
        StepProgram p = f.library.program(f.context(OperationKind.ADOPT, target, null));

        StepOutcome last = runAll(p, f);

        assertThat(last).isInstanceOf(StepOutcome.Done.class);
        assertThat(f.log).containsExactly("update", "tags", "deleteTags"); // no new snapshot row
        assertThat(f.snapshots.stored).isNull();
        assertThat(f.group().tags()).noneMatch(t -> t.key().equals("spot-enabled"));
        assertThat(f.group().tags()).anyMatch(t -> t.key().equals("xamops:autospotting:group-id"));
    }

    @Test
    void adoptWithoutASnapshotFailsBeforeAnyChange() throws IOException {
        AwsConversionFixture f = new AwsConversionFixture(
                FakeAws.asg("web-asg").tags(FakeAws.tag("spot-enabled", "true")).build(), true);
        String target = f.plan("conservative").targetJson();
        StepProgram p = f.library.program(f.context(OperationKind.ADOPT, target, null));

        StepOutcome out = runAll(p, f);

        assertThat(out).isInstanceOfSatisfying(StepOutcome.Failed.class,
                x -> assertThat(x.code()).isEqualTo("SNAPSHOT_MISSING"));
        assertThat(f.writeCalls()).isEmpty();
    }

    // ---- the marker ----

    @Test
    void aFailedMarkerWriteIsRetriedAsTransientUntilTheFifthAttempt() throws IOException {
        AwsConversionFixture f = enableFixture();
        String target = f.plan("conservative").targetJson();
        doThrow(SdkClientException.create("boom")).when(f.write).createOrUpdateTags(any(CreateOrUpdateTagsRequest.class));
        StepProgram p = f.library.program(f.context(OperationKind.ENABLE, target, null));
        Step marker = p.at(6);

        when(f.facts.stepAttempts(any(), Mockito.eq(6))).thenReturn(2);
        assertThat(run(marker, f, 6)).isInstanceOfSatisfying(StepOutcome.Failed.class,
                x -> assertThat(x.kind()).isEqualTo(ErrorKind.TRANSIENT));

        when(f.facts.stepAttempts(any(), Mockito.eq(6))).thenReturn(WriteMarkerAttempts.MAX);
        StepOutcome fifth = run(marker, f, 6);
        assertThat(fifth).isInstanceOfSatisfying(StepOutcome.Done.class, d -> {
            assertThat(d.outputJson()).contains("\"markerWritten\":false").contains("DR-3").contains("MINOR");
        });
    }

    /** The documented attempt limit, read from the step. */
    private static final class WriteMarkerAttempts {
        static final int MAX = com.xammer.cloud.service.autospotting.aws.steps.WriteMarkerStep.MAX_ATTEMPTS;
    }

    // ---- compensation (PRIOR_CONFIG) ----

    @Test
    void aPriorConfigCompensationRestoresTheSavedConfigurationInThreeSteps() throws IOException {
        AwsConversionFixture f = enableFixture();
        com.xammer.cloud.service.autospotting.spi.PlanBody plan = f.plan("conservative");
        f.spotInstances.add("i-1");
        runAll(f.library.program(f.context(OperationKind.CONFIG_APPLY, plan.targetJson(), null)), f);
        f.log.clear();
        StepProgram comp = f.library.program(f.compensating(OperationKind.CONFIG_APPLY, plan.currentJson()));

        assertThat(names(comp)).containsExactly("UPDATE_DISTRIBUTION", "WAIT_ROLLOUT", "VERIFY_TARGET");
        StepOutcome out = runAll(comp, f);

        assertThat(out).isInstanceOf(StepOutcome.Done.class);
        assertThat(f.log).containsExactly("update");
        assertThat(f.group().mixedInstancesPolicy()).isNull(); // back to the original launch-template shape
        assertThat(f.group().capacityRebalance()).isFalse();
    }

    @Test
    void aCompensationWithNoSavedConfigurationFailsWithoutAnyCall() throws IOException {
        AwsConversionFixture f = enableFixture();
        f.plan("conservative");
        StepProgram comp = f.library.program(f.compensating(OperationKind.PROFILE_CHANGE, null));

        assertThat(names(comp)).containsExactly("PRIOR_CONFIG_MISSING");
        assertThat(run(comp.at(0), f, 0)).isInstanceOfSatisfying(StepOutcome.Failed.class,
                x -> assertThat(x.code()).isEqualTo("PRIOR_CONFIG_MISSING"));
        assertThat(f.writeCalls()).isEmpty();
    }

    @Test
    void theFallbackKindsAreNotBuiltHere() {
        AwsConversionFixture f = enableFixture();
        for (OperationKind kind : List.of(OperationKind.FALLBACK_ENTER, OperationKind.FALLBACK_EXIT,
                OperationKind.ACCEPT_BASELINE)) {
            assertThatThrownBy(() -> f.library.program(f.context(kind, "{}", null)))
                    .isInstanceOfSatisfying(AutoSpottingApiException.class,
                            e -> assertThat(e.getCode()).isEqualTo("AS_PROVIDER_NOT_READY"));
        }
        assertThat(new ArrayList<>(f.writeCalls())).isEmpty();
    }
}
