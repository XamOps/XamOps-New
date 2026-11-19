package com.xammer.cloud.service.autospotting.gcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xammer.cloud.domain.autospotting.OperationKind;
import com.xammer.cloud.domain.autospotting.OperationPhase;
import com.xammer.cloud.service.autospotting.core.AutoSpottingApiException;
import com.xammer.cloud.service.autospotting.spi.ErrorKind;
import com.xammer.cloud.service.autospotting.spi.GroupRef;
import com.xammer.cloud.service.autospotting.spi.PlanBody;
import com.xammer.cloud.service.autospotting.spi.PlanRequest;
import com.xammer.cloud.service.autospotting.spi.Profile;
import com.xammer.cloud.service.autospotting.spi.Step;
import com.xammer.cloud.service.autospotting.spi.StepOutcome;
import com.xammer.cloud.domain.autospotting.Provider;
import com.xammer.cloud.service.autospotting.spi.StepProgram;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** MIG_POLICY_UPDATE (plan-phase-27.md 27.4, design-gcp.md §3.5): policy fields only, no gate (p), restored on compensation. */
class GcpPolicyPatchGoldenTest {
    private PlanBody plan(GcpConversionFixture f, String editJson) {
        return f.provider().createPlan(new PlanRequest(new GroupRef(Provider.GCP, 3L, f.key, null),
                OperationKind.MIG_POLICY_UPDATE, new Profile("policy", editJson)), f.gcp.view());
    }

    private StepProgram program(GcpConversionFixture f, PlanBody plan, OperationPhase phase) {
        return f.library.program(f.context(OperationKind.MIG_POLICY_UPDATE, plan.targetJson(), null, phase));
    }

    @Test
    void theProgramIsFourSteps() {
        GcpConversionFixture f = new GcpConversionFixture();
        f.planAgainstLive();
        StepProgram p = program(f, plan(f, "{\"updatePolicy\":{\"maxSurge\":{\"fixed\":6}}}"), OperationPhase.FORWARD);
        assertThat(p.steps().stream().map(Step::name).toList())
                .containsExactly("VERIFY_PRECONDITIONS", "PATCH_POLICY", "WAIT_PATCH_OPERATION", "VERIFY_POLICY");
    }

    @Test
    void thePatchBodyHasPolicyFieldsOnly_noTypeNoTargetSizeNoVersionsNoMinReadySeconds() {
        GcpConversionFixture f = new GcpConversionFixture();
        PlanBody plan = plan(f, "{\"updatePolicy\":{\"maxSurge\":{\"fixed\":6},\"minReadySeconds\":30}}");
        assertThat(plan.eligibility().blockers()).isEmpty();
        f.planAgainstLive();
        StepProgram p = program(f, plan, OperationPhase.FORWARD);
        for (int i = 0; i < 4; i++) {
            StepOutcome o = f.run(p.at(i), i);
            assertThat(o).as(p.at(i).name() + " " + o).isInstanceOf(StepOutcome.Done.class);
        }
        assertThat(f.mutating.patches).hasSize(1);
        JsonNode body = f.mutating.patches.get(0);
        assertThat(body.toString()).isEqualTo("{\"updatePolicy\":{\"maxSurge\":{\"fixed\":6}}}");
        assertThat(f.mutating.calls).containsExactly("patch");
        assertThat(f.mutating.inserted).isEmpty();
    }

    @Test
    void typeIsSentOnlyWhenEdited() {
        GcpConversionFixture f = new GcpConversionFixture();
        f.planAgainstLive();
        PlanBody plan = plan(f, "{\"updatePolicy\":{\"type\":\"PROACTIVE\"}}");
        StepProgram p = program(f, plan, OperationPhase.FORWARD);
        for (int i = 0; i < 2; i++) {
            f.run(p.at(i), i);
        }
        assertThat(f.mutating.patches.get(0).toString()).isEqualTo("{\"updatePolicy\":{\"type\":\"PROACTIVE\"}}");
    }

    @Test
    void anUnmanagedMigsPlanIsReadOnlyAndDoesNotEnqueueAnyCloudWrite() {
        GcpConversionFixture f = new GcpConversionFixture();
        plan(f, "{\"updatePolicy\":{\"maxSurge\":{\"fixed\":6}}}");
        assertThat(f.mutating.calls).isEmpty();
    }

    @Test
    void theWriteClientIsNotGatedBySwitchP_theProgramRunsWithTheConversionGateClosed() {
        GcpConversionFixture f = new GcpConversionFixture();
        f.gateOpen = false;
        f.wire();
        f.planAgainstLive();
        PlanBody plan = plan(f, "{\"updatePolicy\":{\"maxSurge\":{\"fixed\":6}}}");
        StepProgram p = f.provider().executeConversion(f.context(OperationKind.MIG_POLICY_UPDATE, plan.targetJson(), null,
                OperationPhase.FORWARD));
        assertThat(f.run(p.at(0), 0)).isInstanceOf(StepOutcome.Done.class);
        assertThat(f.run(p.at(1), 1)).isInstanceOf(StepOutcome.Done.class);
        // A conversion is still refused by the same gate.
        assertThatThrownBy(() -> f.provider().executeConversion(f.context(OperationKind.ENABLE, f.target(3).toString(), null,
                OperationPhase.FORWARD))).isInstanceOfSatisfying(AutoSpottingApiException.class,
                e -> assertThat(e.getCode()).isEqualTo("AS_PROVIDER_NOT_READY"));
    }

    @Test
    void aTargetSizeEditOnAnAutoscaledMigIsRejectedAtPlanTime() {
        GcpConversionFixture f = new GcpConversionFixture();
        f.gcp.mig().with("status").put("autoscaler", FakeGcp.P + "/regions/us-central1/autoscalers/web-as");
        PlanBody plan = plan(f, "{\"targetSize\":9}");
        assertThat(plan.eligibility().blockers()).extracting(c -> c.code()).containsExactly("GCP_TARGET_SIZE_AUTOSCALED");
    }

    @Test
    void aRegionalSurgeBelowTheZoneCountIsRejectedAtPlanTime() {
        GcpConversionFixture f = new GcpConversionFixture();
        PlanBody plan = plan(f, "{\"updatePolicy\":{\"maxSurge\":{\"fixed\":2}}}");
        assertThat(plan.eligibility().blockers()).extracting(c -> c.code())
                .containsExactly("GCP_POLICY_REGIONAL_SURGE_BELOW_ZONES");
    }

    @Test
    void bothSurgeAndUnavailableZeroIsRejected() {
        GcpConversionFixture f = new GcpConversionFixture();
        PlanBody plan = plan(f, "{\"updatePolicy\":{\"maxSurge\":{\"fixed\":0},\"maxUnavailable\":{\"fixed\":0}}}");
        assertThat(plan.eligibility().blockers()).extracting(c -> c.code()).containsExactly("GCP_POLICY_AT_LEAST_ONE_ABOVE_ZERO");
    }

    @Test
    void theStepRefusesATargetSizeEditOnAnAutoscaledMigEvenIfThePlanSlippedThrough() {
        GcpConversionFixture f = new GcpConversionFixture();
        f.planAgainstLive();
        PlanBody plan = plan(f, "{\"targetSize\":9}");
        f.gcp.mig().with("status").put("autoscaler", FakeGcp.P + "/regions/us-central1/autoscalers/web-as");
        f.planAgainstLive();
        StepProgram p = program(f, plan, OperationPhase.FORWARD);
        assertThat(f.run(p.at(0), 0)).isInstanceOfSatisfying(StepOutcome.Failed.class,
                x -> assertThat(x.code()).isEqualTo("GCP_TARGET_SIZE_AUTOSCALED"));
        assertThat(f.mutating.calls).isEmpty();
    }

    @Test
    void aMissingUpdatePermissionFailsBeforeAnyChange() {
        GcpConversionFixture f = new GcpConversionFixture();
        f.gcp.grantedProject.remove("compute.instanceGroupManagers.update");
        f.planAgainstLive();
        StepProgram p = program(f, plan(f, "{\"updatePolicy\":{\"maxSurge\":{\"fixed\":6}}}"), OperationPhase.FORWARD);
        assertThat(f.run(p.at(0), 0)).isInstanceOfSatisfying(StepOutcome.Failed.class, x -> {
            assertThat(x.kind()).isEqualTo(ErrorKind.PERMISSION);
        });
        assertThat(f.mutating.calls).isEmpty();
    }

    @Test
    void aConflictOnThePolicyPatchIsNeverSuccess() {
        GcpConversionFixture f = new GcpConversionFixture();
        f.planAgainstLive();
        StepProgram p = program(f, plan(f, "{\"updatePolicy\":{\"maxSurge\":{\"fixed\":6}}}"), OperationPhase.FORWARD);
        f.run(p.at(0), 0);
        f.mutating.patchStatusToFail = 409;
        f.mutating.patchFailuresLeft = 3;
        assertThat(f.run(p.at(1), 1)).isInstanceOfSatisfying(StepOutcome.Failed.class, x -> {
            assertThat(x.kind()).isEqualTo(ErrorKind.CONFLICT);
            assertThat(x.code()).isEqualTo("GCP_MIG_UPDATE_CONFLICT");
        });
    }

    @Test
    void theCompensationRestoresThePolicyReadAtPlanTime() {
        GcpConversionFixture f = new GcpConversionFixture();
        f.planAgainstLive();
        PlanBody plan = plan(f, "{\"updatePolicy\":{\"maxSurge\":{\"fixed\":6},\"type\":\"PROACTIVE\"}}");
        StepProgram forward = program(f, plan, OperationPhase.FORWARD);
        for (int i = 0; i < 2; i++) {
            f.run(forward.at(i), i);
        }
        assertThat(f.gcp.mig().path("updatePolicy").path("maxSurge").path("fixed").asInt()).isEqualTo(6);
        StepProgram comp = program(f, plan, OperationPhase.COMPENSATING);
        assertThat(comp.steps().stream().map(Step::name).toList()).containsExactly("PATCH_POLICY", "WAIT_PATCH_OPERATION", "VERIFY_POLICY");
        for (int i = 0; i < 3; i++) {
            assertThat(f.run(comp.at(i), i)).isInstanceOf(StepOutcome.Done.class);
        }
        JsonNode policy = f.gcp.mig().path("updatePolicy");
        assertThat(policy.path("maxSurge").path("fixed").asInt()).isEqualTo(3);
        assertThat(policy.path("type").asText()).isEqualTo("OPPORTUNISTIC");
        JsonNode last = f.mutating.patches.get(f.mutating.patches.size() - 1);
        assertThat(last.fieldNames()).toIterable().containsExactly("updatePolicy");
    }

    @Test
    void theOwnedFingerprintIsUnchangedByAPolicyOnlyChange() {
        GcpConversionFixture f = new GcpConversionFixture();
        String before = GcpFingerprint.of(GcpMigFacts.from(FakeGcp.PROJECT, f.gcp.mig()));
        ((ObjectNode) f.gcp.mig().path("updatePolicy")).with("maxSurge").put("fixed", 6);
        assertThat(GcpFingerprint.of(GcpMigFacts.from(FakeGcp.PROJECT, f.gcp.mig()))).isEqualTo(before);
    }
}
