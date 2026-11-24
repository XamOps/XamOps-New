package com.xammer.cloud.service.autospotting.gcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xammer.cloud.domain.autospotting.OperationKind;
import com.xammer.cloud.domain.autospotting.OperationPhase;
import com.xammer.cloud.service.autospotting.core.AutoSpottingApiException;
import com.xammer.cloud.service.autospotting.gcp.steps.ResolveRevertTargetStep;
import com.xammer.cloud.service.autospotting.spi.ErrorKind;
import com.xammer.cloud.service.autospotting.spi.OperationContext;
import com.xammer.cloud.service.autospotting.spi.PlanBody;
import com.xammer.cloud.service.autospotting.spi.PlanRequest;
import com.xammer.cloud.service.autospotting.spi.Profile;
import com.xammer.cloud.service.autospotting.spi.GroupRef;
import com.xammer.cloud.service.autospotting.spi.Step;
import com.xammer.cloud.service.autospotting.spi.StepOutcome;
import com.xammer.cloud.service.autospotting.spi.StepProgram;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * The GCP REVERT program against a stubbed Compute (plan-phase-28.md 28.1; AC-REV-02, AC-REV-03, AC-REV-04,
 * AC-REV-05; GR-03): exact restore of the original template and update policy, a rebuilt template when the original
 * was deleted, a customer-changed template kept, a revert never blocked by a conversion-only check, and a missing
 * permission refused before any mutation. No cloud call.
 */
class GcpRestoreProgramTest {
    static final List<String> REVERT = List.of("VERIFY_PRECONDITIONS", "RESOLVE_REVERT_TARGET", "WAIT_TEMPLATE_OPERATION",
            "PATCH_MIG", "WAIT_PATCH_OPERATION", "WAIT_ROLLOUT", "VERIFY_INSTANCES", "RESTORE_UPDATE_POLICY", "FINALIZE");
    private static final UUID REVERT_OP = UUID.fromString("bbbbbbbb-1111-2222-3333-444444444444");

    /** ENABLE at floor {@code n} driven to completion: the MIG is converted and the original is saved. */
    private void enable(GcpConversionFixture f, int n) {
        f.planAgainstLive();
        StepProgram p = f.library.program(f.context(OperationKind.ENABLE, f.target(n).toString(), null, OperationPhase.FORWARD));
        for (int i = 0; i <= 5; i++) {
            assertThat(f.run(p.at(i), i)).isInstanceOf(StepOutcome.Done.class);
        }
        f.completeRollout(n, f.registry.findByOp(f.opId).orElseThrow().selfLink());
        assertThat(f.pollDone(p.at(6), 6)).isInstanceOf(StepOutcome.Done.class);
        for (int i = 7; i <= 9; i++) {
            assertThat(f.run(p.at(i), i)).isInstanceOf(StepOutcome.Done.class);
        }
        f.mutating.calls.clear();
        f.mutating.patches.clear();
        f.mutating.inserted.clear();
        f.lastProgress = null;
    }

    private StepProgram revert(GcpConversionFixture f) {
        f.planAgainstLive();
        return f.restoreProgram.program(new OperationContext(REVERT_OP, OperationKind.REVERT, f.group(), null, null,
                OperationPhase.FORWARD, f.planId, null));
    }

    /** The cloud follows the revert PATCH: every instance on {@code template}, the MIG stable. */
    private void rollOutTo(GcpConversionFixture f, String template) {
        f.gcp.managedInstances.clear();
        for (int i = 0; i < 6; i++) {
            f.gcp.managedInstances.add(GcpConversionFixture.instance(i, null, template, "NONE"));
        }
        ObjectNode status = (ObjectNode) f.gcp.mig().path("status");
        status.put("isStable", true);
        ((ObjectNode) status.path("versionTarget")).put("isReached", true);
    }

    private static void done(StepOutcome o) {
        assertThat(o).as(o.toString()).isInstanceOf(StepOutcome.Done.class);
    }

    private void runWholeRevert(GcpConversionFixture f, StepProgram p, String template) {
        for (int i = 0; i <= 4; i++) {
            done(f.run(p.at(i), i));
        }
        rollOutTo(f, template);
        done(f.pollDone(p.at(5), 5));
        for (int i = 6; i <= 8; i++) {
            done(f.run(p.at(i), i));
        }
    }

    @Test
    void theRevertProgramHasTheNineSteps() {
        GcpConversionFixture f = new GcpConversionFixture();
        enable(f, 3);
        assertThat(revert(f).steps().stream().map(Step::name).toList()).isEqualTo(REVERT);
    }

    @Test
    void anExactRevertRestoresTheOriginalTemplateAndUpdatePolicyAndRetiresTheSpotCopy() {
        GcpConversionFixture f = new GcpConversionFixture();
        JsonNode originalPolicy = f.gcp.mig().path("updatePolicy").deepCopy();
        enable(f, 3);
        String spot = f.registry.findByOp(f.opId).orElseThrow().selfLink();
        assertThat(f.gcp.mig().path("versions")).hasSize(2);
        StepProgram p = revert(f);
        runWholeRevert(f, p, GcpConversionFixture.ORIGINAL);
        // The exact original: one version on the original self-link, no Spot version, the customer's own policy.
        assertThat(f.gcp.mig().path("versions")).hasSize(1);
        assertThat(f.gcp.mig().path("versions").path(0).path("instanceTemplate").asText()).isEqualTo(GcpConversionFixture.ORIGINAL);
        assertThat(f.gcp.mig().path("versions").toString()).doesNotContain(spot).doesNotContain("xas-spot");
        // Every field the customer had is back (an unset field is the API default, which XamOps's own policy equals).
        assertThat(GcpTargets.policyHas(f.gcp.mig().path("updatePolicy"), originalPolicy)).isTrue();
        assertThat(f.gcp.mig().path("updatePolicy").path("type").asText()).isEqualTo("OPPORTUNISTIC");
        // Surge-first on the way (maxUnavailable 0), then the snapshot policy; nothing created, nothing deleted.
        assertThat(f.mutating.patches).hasSize(2);
        assertThat(f.mutating.patches.get(0).path("updatePolicy").path("maxUnavailable").path("fixed").asInt()).isZero();
        assertThat(f.mutating.inserted).as("an exact revert creates no template").isEmpty();
        assertThat(f.mutating.deleted).isEmpty();
        // The Spot copy (and with it the PO-20 label) is retired, never deleted inline.
        assertThat(f.registry.findByOp(f.opId).orElseThrow().status()).isEqualTo("RETIRED");
        assertThat(f.registry.findByOp(f.opId).orElseThrow().deleteAfter()).isNotNull();
    }

    @Test
    void theResolveStepOfAnExactRevertIsNotMutating_theRevertingTransitionComesWithThePatch() {
        GcpConversionFixture f = new GcpConversionFixture();
        enable(f, 3);
        StepProgram p = revert(f);
        assertThat(p.at(1)).isInstanceOf(ResolveRevertTargetStep.class);
        assertThat(p.at(0).mutates()).isFalse();
        assertThat(p.at(1).mutates()).isFalse();
        assertThat(p.at(2).mutates()).isFalse();
        assertThat(p.at(3).mutates()).isTrue();
    }

    @Test
    void aDeletedOriginalIsRebuiltFromTheCapturedJsonAsAnOwnedTemplateAndTheRevertIsNotExact() {
        GcpConversionFixture f = new GcpConversionFixture();
        enable(f, 0); // Full Spot: nothing runs on the original, so the customer can delete it
        String machineType = f.gcp.template().path("properties").path("machineType").asText();
        f.gcp.templates.remove(GcpConversionFixture.ORIGINAL);
        when(f.facts.planConfirmAcks(any())).thenReturn(Optional.of("[\"REVERT_TEMPLATE_REBUILT\"]"));
        StepProgram p = revert(f);
        assertThat(p.at(1).mutates()).as("the rebuild is the first mutating step").isTrue();
        for (int i = 0; i <= 4; i++) {
            done(f.run(p.at(i), i));
        }
        assertThat(f.mutating.inserted).hasSize(1);
        JsonNode rebuilt = f.mutating.inserted.get(0);
        String name = rebuilt.path("name").asText();
        assertThat(name).isEqualTo("web-mig-xas-11111111-r2");
        assertThat(rebuilt.path("properties").path("machineType").asText()).isEqualTo(machineType);
        // A rebuild is the customer's configuration: not Spot, and the XamOps attribution label is not carried (PO-20).
        assertThat(rebuilt.path("properties").path("scheduling").path("provisioningModel").asText()).isNotEqualTo("SPOT");
        assertThat(rebuilt.path("properties").path("labels").has("xamops-autospotting")).isFalse();
        assertThat(rebuilt.path("properties").path("labels").path("env").asText()).isEqualTo("prod");
        GcpTemplateRegistry.Row row = f.registry.findLive("global", name).orElseThrow();
        assertThat(row.role()).isEqualTo("REBUILT");
        assertThat(row.status()).isEqualTo("IN_USE");
        String link = row.selfLink();
        rollOutTo(f, link);
        done(f.pollDone(p.at(5), 5));
        for (int i = 6; i <= 8; i++) {
            done(f.run(p.at(i), i));
        }
        assertThat(f.gcp.mig().path("versions").path(0).path("instanceTemplate").asText()).isEqualTo(link);
        assertThat(f.mutating.deleted).isEmpty();
    }

    @Test
    void theRevertPlanStatesTheRebuildAndKeepsItOutOfTheConfirmBlockers() {
        GcpConversionFixture f = new GcpConversionFixture();
        enable(f, 0);
        f.gcp.templates.remove(GcpConversionFixture.ORIGINAL);
        PlanBody body = f.provider().createPlan(new PlanRequest(new GroupRef(com.xammer.cloud.domain.autospotting.Provider.GCP,
                3L, f.key, null), OperationKind.REVERT, new Profile("conservative", null)), f.gcp.view());
        assertThat(body.eligibility().blockers()).isEmpty();
        assertThat(body.eligibility().warnings()).extracting(c -> c.id()).contains("REVERT_TEMPLATE_REBUILT");
        assertThat(body.targetJson()).contains("\"exact\":false").contains("\"rebuiltTemplate\":true")
                .contains("\"removesAttributionLabel\":true");
    }

    @Test
    void aCustomerChangedStandardTemplateIsKeptAndOnlyTheSpotVersionGoes() {
        GcpConversionFixture f = new GcpConversionFixture();
        enable(f, 3);
        String changed = FakeGcp.P + "/global/instanceTemplates/web-tpl-2";
        f.gcp.templates.put(changed, GcpConversionFixture.JSON.createObjectNode().put("id", "8800").put("name", "web-tpl-2"));
        ((ObjectNode) f.gcp.mig().path("versions").path(0)).put("instanceTemplate", changed);
        when(f.facts.planConfirmAcks(any())).thenReturn(Optional.of("[\"REVERT_KEPT_CUSTOMER_TEMPLATE\"]"));
        StepProgram p = revert(f);
        runWholeRevert(f, p, changed);
        assertThat(f.gcp.mig().path("versions")).hasSize(1);
        assertThat(f.gcp.mig().path("versions").path(0).path("instanceTemplate").asText()).isEqualTo(changed);
        assertThat(f.mutating.inserted).isEmpty();
    }

    @Test
    void aKeptOrRebuiltTargetNeedsItsAcknowledgementAtStepZero() {
        GcpConversionFixture f = new GcpConversionFixture();
        enable(f, 3);
        String changed = FakeGcp.P + "/global/instanceTemplates/web-tpl-2";
        f.gcp.templates.put(changed, GcpConversionFixture.JSON.createObjectNode().put("id", "8800"));
        ((ObjectNode) f.gcp.mig().path("versions").path(0)).put("instanceTemplate", changed);
        StepProgram p = revert(f);
        StepOutcome o = f.run(p.at(0), 0);
        assertThat(o).isInstanceOf(StepOutcome.Failed.class);
        assertThat(((StepOutcome.Failed) o).code()).isEqualTo("ACK_REQUIRED");
        assertThat(f.mutating.calls).isEmpty();
    }

    @Test
    void theSnapshotsFlexibilityPolicyIsRestoredAndTheSpotVersionAlwaysRemoved() {
        GcpConversionFixture f = new GcpConversionFixture();
        ObjectNode flex = GcpConversionFixture.JSON.createObjectNode();
        flex.putObject("instanceSelections").putObject("sel-a").putArray("machineTypes").add("n2-standard-4");
        f.gcp.mig().set("instanceFlexibilityPolicy", flex);
        enable(f, 3);
        f.gcp.mig().remove("instanceFlexibilityPolicy");
        StepProgram p = revert(f);
        runWholeRevert(f, p, GcpConversionFixture.ORIGINAL);
        assertThat(f.mutating.patches.get(0).path("instanceFlexibilityPolicy")).isEqualTo(flex);
        assertThat(f.gcp.mig().path("instanceFlexibilityPolicy")).isEqualTo(flex);
        assertThat(f.gcp.mig().path("versions").toString()).doesNotContain("xas-spot");
    }

    @Test
    void aRevertProceedsDespiteANewSizeTooSmallBlocker_itNeverRunsTheConversionChecks() {
        GcpConversionFixture f = new GcpConversionFixture();
        enable(f, 3);
        // The group has since shrunk to one VM: a conversion would now be GCP_SIZE_TOO_SMALL.
        f.gcp.mig().put("targetSize", 1);
        f.gcp.calls.clear();
        PlanBody body = f.provider().createPlan(new PlanRequest(new GroupRef(com.xammer.cloud.domain.autospotting.Provider.GCP,
                3L, f.key, null), OperationKind.REVERT, new Profile("conservative", null)), f.gcp.view());
        assertThat(body.eligibility().blockers()).isEmpty();
        StepProgram p = revert(f);
        done(f.run(p.at(0), 0));
        assertThat(f.gcp.calls).as("no conversion eligibility reads").doesNotContain("regions.get", "machineTypes.get",
                "backendServices.aggregatedList");
    }

    @Test
    void aMissingUpdatePermissionPutsRevertNeedsPermissionOnThePlan() {
        GcpConversionFixture f = new GcpConversionFixture();
        enable(f, 3);
        f.gcp.grantedProject.remove("compute.instanceGroupManagers.update");
        PlanBody body = f.provider().createPlan(new PlanRequest(new GroupRef(com.xammer.cloud.domain.autospotting.Provider.GCP,
                3L, f.key, null), OperationKind.REVERT, new Profile("conservative", null)), f.gcp.view());
        assertThat(body.eligibility().blockers()).extracting(c -> c.code()).containsExactly("REVERT_NEEDS_PERMISSION");
        assertThat(body.revertPathJson()).contains("REVERT_NEEDS_PERMISSION").contains("compute.instanceGroupManagers.update")
                .contains("xamopsAutoSpotting");
    }

    @Test
    void theCreatePermissionIsRequiredOnlyWhenATemplateMustBeRebuilt() {
        GcpConversionFixture f = new GcpConversionFixture();
        enable(f, 3);
        f.gcp.grantedProject.remove("compute.instanceTemplates.create");
        PlanBody exact = f.provider().createPlan(new PlanRequest(new GroupRef(com.xammer.cloud.domain.autospotting.Provider.GCP,
                3L, f.key, null), OperationKind.REVERT, new Profile("conservative", null)), f.gcp.view());
        assertThat(exact.eligibility().blockers()).isEmpty();
        GcpConversionFixture g = new GcpConversionFixture();
        enable(g, 0);
        g.gcp.templates.remove(GcpConversionFixture.ORIGINAL);
        g.gcp.grantedProject.remove("compute.instanceTemplates.create");
        PlanBody rebuild = g.provider().createPlan(new PlanRequest(new GroupRef(com.xammer.cloud.domain.autospotting.Provider.GCP,
                3L, g.key, null), OperationKind.REVERT, new Profile("conservative", null)), g.gcp.view());
        assertThat(rebuild.eligibility().blockers()).extracting(c -> c.code()).containsExactly("REVERT_NEEDS_PERMISSION");
    }

    @Test
    void aStepZeroThatFindsThePermissionMissingFailsBeforeAnyMutation() {
        GcpConversionFixture f = new GcpConversionFixture();
        enable(f, 3);
        StepProgram p = revert(f);
        f.gcp.grantedProject.remove("compute.instanceGroupManagers.update");
        StepOutcome o = f.run(p.at(0), 0);
        assertThat(o).isInstanceOf(StepOutcome.Failed.class);
        StepOutcome.Failed failed = (StepOutcome.Failed) o;
        assertThat(failed.code()).isEqualTo("REVERT_NEEDS_PERMISSION");
        assertThat(failed.kind()).isEqualTo(ErrorKind.PERMISSION);
        assertThat(f.mutating.calls).as("nothing mutated").isEmpty();
        assertThat(p.at(0).mutates()).isFalse();
    }

    @Test
    void aForeignRolloutIsWaitedForButOurOwnRolloutIsNot() {
        GcpConversionFixture f = new GcpConversionFixture();
        enable(f, 3);
        ((ObjectNode) f.gcp.mig().path("status")).put("isStable", false);
        StepProgram own = revert(f);
        done(f.run(own.at(0), 0)); // xas-* versions: a rollout XamOps started is not foreign
        GcpConversionFixture g = new GcpConversionFixture();
        enable(g, 3);
        // The customer swapped the versions for their own and is rolling them out.
        com.fasterxml.jackson.databind.node.ArrayNode vs = GcpConversionFixture.JSON.createArrayNode();
        vs.addObject().put("instanceTemplate", GcpConversionFixture.ORIGINAL);
        g.gcp.mig().set("versions", vs);
        ((ObjectNode) g.gcp.mig().path("status")).put("isStable", false);
        StepProgram foreign = revert(g);
        StepOutcome o = g.run(foreign.at(0), 0);
        assertThat(o).isInstanceOf(StepOutcome.Failed.class);
        assertThat(((StepOutcome.Failed) o).code()).isEqualTo("FOREIGN_CHANGE");
        assertThat(((StepOutcome.Failed) o).kind()).isEqualTo(ErrorKind.CONFLICT);
    }

    @Test
    void aStaleRevertPlanFailsStepZeroAndTouchesNothing() {
        GcpConversionFixture f = new GcpConversionFixture();
        enable(f, 3);
        StepProgram p = revert(f);
        ((ObjectNode) f.gcp.mig()).put("instanceTemplate", FakeGcp.P + "/global/instanceTemplates/other");
        ((ObjectNode) f.gcp.mig().path("updatePolicy")).put("minimalAction", "RESTART");
        StepOutcome o = f.run(p.at(0), 0);
        assertThat(((StepOutcome.Failed) o).code()).isEqualTo("PLAN_STALE");
        assertThat(f.mutating.calls).isEmpty();
    }

    @Test
    void theRevertPatchIsConvergent_aResumedStepSendsNoSecondPatch() {
        GcpConversionFixture f = new GcpConversionFixture();
        enable(f, 3);
        StepProgram p = revert(f);
        for (int i = 0; i <= 3; i++) {
            done(f.run(p.at(i), i));
        }
        assertThat(f.mutating.patches).hasSize(1);
        done(f.run(p.at(3), 3));
        assertThat(f.mutating.patches).as("the live MIG already shows the target").hasSize(1);
    }

    @Test
    void aRevertWithNoSavedOriginalFailsSafelyBeforeAnyChange() {
        GcpConversionFixture f = new GcpConversionFixture();
        StepProgram p = revert(f);
        assertThat(p.steps().stream().map(Step::name)).containsExactly("REVERT_SNAPSHOT_MISSING");
        StepOutcome o = f.run(p.at(0), 0);
        assertThat(((StepOutcome.Failed) o).code()).isEqualTo("REVERT_SNAPSHOT_MISSING");
        assertThat(f.mutating.calls).isEmpty();
    }

    @Test
    void theGateIsASecondLock_theProviderBuildsNoRevertWhileItIsClosed() {
        GcpConversionFixture f = new GcpConversionFixture();
        f.gateOpen = false;
        f.wire();
        OperationContext ctx = new OperationContext(REVERT_OP, OperationKind.REVERT, f.group(), null, null,
                OperationPhase.FORWARD, f.planId, null);
        assertThatThrownBy(() -> f.provider().restore(ctx, null)).isInstanceOf(AutoSpottingApiException.class)
                .hasMessageContaining("not ready");
        assertThat(f.mutating.calls).isEmpty();
    }
}
