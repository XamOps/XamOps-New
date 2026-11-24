package com.xammer.cloud.service.autospotting.gcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xammer.cloud.domain.autospotting.CompensationTarget;
import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.OperationKind;
import com.xammer.cloud.domain.autospotting.OperationPhase;
import com.xammer.cloud.domain.autospotting.SpotGroup;
import com.xammer.cloud.service.autospotting.core.AutoSpottingApiException;
import com.xammer.cloud.service.autospotting.spi.FallbackSteps;
import com.xammer.cloud.service.autospotting.spi.OperationContext;
import com.xammer.cloud.service.autospotting.spi.Step;
import com.xammer.cloud.service.autospotting.spi.StepOutcome;
import com.xammer.cloud.service.autospotting.spi.StepProgram;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The GCP fallback programs against a stubbed Compute (design-gcp.md §7.3; plan-phase-28.md 28.2; AC-HLT-02,
 * AC-HLT-03, AC-HLT-04): a single {@code xas-standard} version on the original template with {@code maxUnavailable=0},
 * the verified-PATCH output the runner turns into FALLBACK, the tail wait with FALLBACK to DEGRADED signals, the
 * bounded CAPACITY_PENDING end that leaves the XamOps policy in place, and the exit and its compensation. No cloud call.
 */
class GcpFallbackProgramsTest {
    static final List<String> ENTER = List.of("VERIFY_PRECONDITIONS", "PATCH_MIG", "WAIT_PATCH_OPERATION",
            "VERIFY_FALLBACK_APPLIED", "WAIT_CAPACITY", "RESTORE_UPDATE_POLICY");
    static final List<String> EXIT = List.of("PATCH_MIG", "WAIT_PATCH_OPERATION", "WAIT_ROLLOUT", "VERIFY_INSTANCES",
            "RESTORE_UPDATE_POLICY", "FINALIZE");
    private static final UUID FB_OP = UUID.fromString("cccccccc-1111-2222-3333-444444444444");

    private SpotGroup converted(GcpConversionFixture f, int n) {
        ObjectNode target = f.target(n); // the confirmed target carries the customer's own policy as it was before enable
        f.enableCompleted(n);
        SpotGroup g = f.group();
        g.setState(GroupState.ACTIVE);
        g.setDesiredConfig(target.toString());
        return g;
    }

    private StepProgram enter(GcpConversionFixture f, SpotGroup g) {
        return f.provider().executeConversion(new OperationContext(FB_OP, OperationKind.FALLBACK_ENTER, g, null, null,
                OperationPhase.FORWARD, null, CompensationTarget.NONE));
    }

    private static void standardOnly(GcpConversionFixture f, int running) {
        f.gcp.managedInstances.clear();
        for (int i = 0; i < running; i++) {
            f.gcp.managedInstances.add(GcpConversionFixture.instance(i, GcpMigFacts.STANDARD_VERSION,
                    GcpConversionFixture.ORIGINAL, "NONE"));
        }
        ObjectNode status = (ObjectNode) f.gcp.mig().path("status");
        status.put("isStable", true);
        ((ObjectNode) status.path("versionTarget")).put("isReached", true);
    }

    private static void done(StepOutcome o) {
        assertThat(o).as(o.toString()).isInstanceOf(StepOutcome.Done.class);
    }

    @Test
    void theEnterProgramHasTheSixSteps_andOnlyThePatchAndTheRestoreMutate() {
        GcpConversionFixture f = new GcpConversionFixture();
        StepProgram p = enter(f, converted(f, 3));
        assertThat(p.steps().stream().map(Step::name).toList()).isEqualTo(ENTER);
        assertThat(p.steps().stream().filter(Step::mutates).map(Step::name)).containsExactly("PATCH_MIG", "RESTORE_UPDATE_POLICY");
    }

    @Test
    void theEnterPatchIsASingleStandardVersionOnTheOriginalTemplateWithSurgeFirst() {
        GcpConversionFixture f = new GcpConversionFixture();
        StepProgram p = enter(f, converted(f, 3));
        done(f.run(p.at(0), 0));
        done(f.run(p.at(1), 1));
        assertThat(f.mutating.patches).hasSize(1);
        JsonNode body = f.mutating.patches.get(0);
        assertThat(body.path("versions")).hasSize(1);
        assertThat(body.path("versions").path(0).path("name").asText()).isEqualTo("xas-standard");
        assertThat(body.path("versions").path(0).path("instanceTemplate").asText()).isEqualTo(GcpConversionFixture.ORIGINAL);
        assertThat(body.path("versions").path(0).has("targetSize")).isFalse();
        assertThat(body.path("updatePolicy").path("maxUnavailable").path("fixed").asInt()).isZero();
        assertThat(body.path("updatePolicy").path("type").asText()).isEqualTo("PROACTIVE");
        assertThat(f.mutating.inserted).as("a fallback creates no template").isEmpty();
        // The verify step reports the owned fingerprint the runner stores as fallback_fingerprint.
        StepOutcome applied = f.run(p.at(3), 3);
        done(applied);
        assertThat(((StepOutcome.Done) applied).outputJson()).contains(FallbackSteps.FINGERPRINT)
                .contains(GcpFingerprint.planHash(GcpMigFacts.from(FakeGcp.PROJECT, f.gcp.mig())));
    }

    @Test
    void theVerifyStepWaitsUntilTheMigShowsTheSingleStandardVersion() {
        GcpConversionFixture f = new GcpConversionFixture();
        StepProgram p = enter(f, converted(f, 3));
        assertThat(f.run(p.at(3), 3)).as("the converted MIG is not in fallback shape yet").isInstanceOf(StepOutcome.InProgress.class);
    }

    @Test
    void theTailWaitReportsShortThenDegradesAtTwentyMinutesAndEndsWhenTheRolloutCompletes() {
        GcpConversionFixture f = new GcpConversionFixture();
        StepProgram p = enter(f, converted(f, 3));
        done(f.run(p.at(1), 1));
        standardOnly(f, 4); // standard capacity is short too
        StepOutcome first = f.run(p.at(4), 4);
        assertThat(first).isInstanceOf(StepOutcome.InProgress.class);
        String progress = ((StepOutcome.InProgress) first).progressJson();
        assertThat(progress).contains("\"capacity\":\"SHORT\"").contains("\"degrade\":false");
        f.lastProgress = progress;
        f.clock.advance(Duration.ofMinutes(21));
        StepOutcome later = f.run(p.at(4), 4);
        assertThat(later).isInstanceOf(StepOutcome.InProgress.class);
        assertThat(((StepOutcome.InProgress) later).progressJson()).contains("\"degrade\":true");
        f.lastProgress = ((StepOutcome.InProgress) later).progressJson();
        standardOnly(f, 6); // capacity returns
        StepOutcome fin = f.pollDone(p.at(4), 4);
        done(fin);
        assertThat(((StepOutcome.Done) fin).outputJson()).contains("\"capacity\":\"OK\"").doesNotContain("CAPACITY_PENDING");
        StepOutcome restored = f.run(p.at(5), 5);
        done(restored);
        assertThat(f.mutating.patches).as("the customer policy is restored once the rollout finished").hasSize(2);
        // The runner re-stores the fingerprint with the final policy, so a drift sweep compares the hash the MIG has.
        assertThat(((StepOutcome.Done) restored).outputJson()).contains(FallbackSteps.FINGERPRINT)
                .contains(GcpFingerprint.planHash(GcpMigFacts.from(FakeGcp.PROJECT, f.gcp.mig())));
    }

    @Test
    void aShortageThatOutlastsTwoHoursEndsCapacityPendingAndKeepsTheXamOpsPolicy() {
        GcpConversionFixture f = new GcpConversionFixture();
        StepProgram p = enter(f, converted(f, 3));
        done(f.run(p.at(1), 1));
        standardOnly(f, 4);
        StepOutcome o = f.run(p.at(4), 4);
        f.lastProgress = ((StepOutcome.InProgress) o).progressJson();
        f.clock.advance(Duration.ofMinutes(121));
        StepOutcome end = f.run(p.at(4), 4);
        done(end);
        assertThat(((StepOutcome.Done) end).outputJson()).contains("CAPACITY_PENDING");
        StepOutcome restore = f.run(p.at(5), 5);
        done(restore);
        assertThat(((StepOutcome.Done) restore).outputJson()).contains("CAPACITY_PENDING").contains("\"restored\":false");
        assertThat(f.mutating.patches).as("no second PATCH: the XamOps policy stays").hasSize(1);
        assertThat(f.gcp.mig().path("updatePolicy").path("maxUnavailable").path("fixed").asInt()).isZero();
        // A later restore (an exit or a revert ends with it) puts the customer's policy back.
    }

    @Test
    void theSpotCopyIsKeptForTheExitAndNothingIsRetiredByAFallback() {
        GcpConversionFixture f = new GcpConversionFixture();
        StepProgram p = enter(f, converted(f, 3));
        done(f.run(p.at(1), 1));
        assertThat(p.steps().stream().map(Step::name)).doesNotContain("FINALIZE");
        assertThat(f.registry.findByOp(f.opId).orElseThrow().status()).isEqualTo("IN_USE");
    }

    @Test
    void aFullSpotGroupFallsBackToTheSnapshotsOriginalTemplate() {
        GcpConversionFixture f = new GcpConversionFixture();
        StepProgram p = enter(f, converted(f, 0));
        done(f.run(p.at(0), 0));
        done(f.run(p.at(1), 1));
        JsonNode v = f.mutating.patches.get(0).path("versions");
        assertThat(v).hasSize(1);
        assertThat(v.path(0).path("instanceTemplate").asText()).isEqualTo(GcpConversionFixture.ORIGINAL);
    }

    @Test
    void theExitIsTheEnableProgramFromStepFourWithTheExistingSpotTemplateAndDesiredConfig() {
        GcpConversionFixture f = new GcpConversionFixture();
        SpotGroup g = converted(f, 3);
        String spot = f.registry.findByOp(f.opId).orElseThrow().selfLink();
        StepProgram enter = enter(f, g);
        done(f.run(enter.at(1), 1));
        standardOnly(f, 6);
        f.mutating.patches.clear();
        g.setState(GroupState.FALLBACK);
        StepProgram exit = f.provider().executeConversion(new OperationContext(FB_OP, OperationKind.FALLBACK_EXIT, g,
                g.getDesiredConfig(), null, OperationPhase.FORWARD, null, CompensationTarget.FALLBACK));
        assertThat(exit.steps().stream().map(Step::name).toList()).isEqualTo(EXIT);
        done(f.run(exit.at(0), 0));
        JsonNode v = f.mutating.patches.get(0).path("versions");
        assertThat(v).hasSize(2);
        assertThat(v.path(0).path("name").asText()).isEqualTo("xas-standard");
        assertThat(v.path(0).path("targetSize").path("fixed").asInt()).isEqualTo(3);
        assertThat(v.path(1).path("instanceTemplate").asText()).as("the existing Spot copy, no new template").isEqualTo(spot);
        assertThat(f.mutating.inserted).isEmpty();
    }

    @Test
    void theExitCompensatesBackToTheSingleStandardVersion() {
        GcpConversionFixture f = new GcpConversionFixture();
        SpotGroup g = converted(f, 3);
        StepProgram enter = enter(f, g);
        done(f.run(enter.at(1), 1));
        g.setState(GroupState.APPLYING);
        StepProgram comp = f.provider().executeConversion(new OperationContext(FB_OP, OperationKind.FALLBACK_EXIT, g,
                g.getDesiredConfig(), null, OperationPhase.COMPENSATING, null, CompensationTarget.FALLBACK));
        assertThat(comp.steps().stream().map(Step::name).toList()).containsExactly("PATCH_MIG", "WAIT_PATCH_OPERATION",
                "WAIT_ROLLOUT", "VERIFY_INSTANCES", "RESTORE_UPDATE_POLICY");
        assertThat(GcpTargets.matches(GcpMigFacts.from(FakeGcp.PROJECT, f.gcp.mig()),
                GcpTargets.fallbackVersions(GcpConversionFixture.ORIGINAL))).isTrue();
    }

    @Test
    void anExitWithNoDesiredConfigIsRefusedAndAFallbackNeverCompensatesTowardSpot() {
        GcpConversionFixture f = new GcpConversionFixture();
        SpotGroup g = converted(f, 3);
        assertThatThrownBy(() -> f.provider().executeConversion(new OperationContext(FB_OP, OperationKind.FALLBACK_EXIT, g,
                null, null, OperationPhase.FORWARD, null, CompensationTarget.FALLBACK)))
                .isInstanceOf(AutoSpottingApiException.class);
        assertThatThrownBy(() -> f.provider().executeConversion(new OperationContext(FB_OP, OperationKind.FALLBACK_ENTER, g,
                null, null, OperationPhase.COMPENSATING, null, CompensationTarget.NONE)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theGateIsASecondLock_aFallbackProgramIsNotBuiltWhileItIsClosed() {
        GcpConversionFixture f = new GcpConversionFixture();
        f.gateOpen = false;
        f.wire();
        SpotGroup g = f.group();
        assertThatThrownBy(() -> enter(f, g)).isInstanceOf(AutoSpottingApiException.class).hasMessageContaining("not ready");
        assertThat(f.mutating.calls).isEmpty();
    }
}
