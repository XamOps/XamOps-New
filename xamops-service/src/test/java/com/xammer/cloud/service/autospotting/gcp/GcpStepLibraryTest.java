package com.xammer.cloud.service.autospotting.gcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xammer.cloud.domain.autospotting.OperationKind;
import com.xammer.cloud.domain.autospotting.OperationPhase;
import com.xammer.cloud.service.autospotting.core.AutoSpottingApiException;
import com.xammer.cloud.service.autospotting.spi.ErrorKind;
import com.xammer.cloud.service.autospotting.spi.Step;
import com.xammer.cloud.service.autospotting.spi.StepOutcome;
import com.xammer.cloud.service.autospotting.spi.StepProgram;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * The ENABLE program end to end against a stubbed Compute (plan-phase-27.md 27.3): original recorded before any
 * change, lossless Spot copy, surge-first PATCH, success only on versionTarget plus stability, policy restored,
 * conflicts never success, only XamOps templates retired, never deleted inline. No cloud call.
 */
class GcpStepLibraryTest {
    static final List<String> FORWARD = List.of("VERIFY_PRECONDITIONS", "PERSIST_SNAPSHOT", "CREATE_SPOT_TEMPLATE",
            "WAIT_TEMPLATE_OPERATION", "PATCH_MIG", "WAIT_PATCH_OPERATION", "WAIT_ROLLOUT", "VERIFY_INSTANCES",
            "RESTORE_UPDATE_POLICY", "FINALIZE");

    private StepProgram enable(GcpConversionFixture f, int n) {
        f.planAgainstLive();
        return f.library.program(f.context(OperationKind.ENABLE, f.target(n).toString(), null, OperationPhase.FORWARD));
    }

    private static List<String> names(StepProgram p) {
        return p.steps().stream().map(Step::name).toList();
    }

    private static void done(StepOutcome o) {
        assertThat(o).as(o.toString()).isInstanceOf(StepOutcome.Done.class);
    }

    @Test
    void enableProgramHasTheTenSteps() {
        assertThat(names(enable(new GcpConversionFixture(), 3))).isEqualTo(FORWARD);
    }

    @Test
    void theOriginalIsRecordedBeforeAnyCloudChangeAndTheCopyIsLossless() {
        GcpConversionFixture f = new GcpConversionFixture();
        StepProgram p = enable(f, 3);
        done(f.run(p.at(0), 0));
        done(f.run(p.at(1), 1));
        assertThat(f.mutating.calls).as("nothing changed before the snapshot is saved").isEmpty();
        GcpSnapshotStore.Stored saved = f.snapshots.findByOp(f.opId).orElseThrow();
        assertThat(saved.exact()).isTrue();
        assertThat(saved.payload()).contains(GcpConversionFixture.ORIGINAL).contains("OPPORTUNISTIC");
        done(f.run(p.at(2), 2));
        assertThat(f.mutating.inserted).hasSize(1);
        JsonNode copy = f.mutating.inserted.get(0);
        JsonNode source = f.gcp.template();
        assertThat(copy.path("properties").path("scheduling").path("provisioningModel").asText()).isEqualTo("SPOT");
        assertThat(copy.path("properties").path("machineType")).isEqualTo(source.path("properties").path("machineType"));
        assertThat(copy.path("properties").path("disks")).isEqualTo(source.path("properties").path("disks"));
        assertThat(copy.path("properties").path("labels").path("env").asText()).isEqualTo("prod");
        assertThat(copy.path("properties").path("labels").path("xamops-autospotting").asText()).isEqualTo(f.groupId.toString());
        assertThat(f.mutating.requestIds.get(0).toString()).isNotBlank();
        assertThat(f.registry.findByOp(f.opId)).isPresent();
        assertThat(f.registry.findByOp(f.opId).get().status()).isEqualTo("CREATING");
        done(f.run(p.at(3), 3));
        assertThat(f.registry.findByOp(f.opId).get().status()).isEqualTo("IN_USE");
        assertThat(f.registry.findByOp(f.opId).get().gcpId()).isNotBlank();
    }

    @Test
    void successIsRecordedOnlyWhenVersionTargetIsReachedAndStable_neverAtPatchAcceptance() {
        GcpConversionFixture f = new GcpConversionFixture();
        StepProgram p = enable(f, 3);
        for (int i = 0; i <= 5; i++) {
            done(f.run(p.at(i), i));
        }
        // The PATCH was accepted and the MIG shows the new versions, but no instance has moved yet.
        StepOutcome mid = f.run(p.at(6), 6);
        assertThat(mid).isInstanceOf(StepOutcome.InProgress.class);
        f.lastProgress = ((StepOutcome.InProgress) mid).progressJson();
        // Even with the replacement partly done, an unreached versionTarget keeps waiting.
        String spot = f.registry.findByOp(f.opId).orElseThrow().selfLink();
        f.completeRollout(3, spot);
        ((ObjectNode) f.gcp.mig().path("status").path("versionTarget")).put("isReached", false);
        f.clock.advance(Duration.ofMinutes(5));
        assertThat(f.run(p.at(6), 6)).isInstanceOf(StepOutcome.InProgress.class);
        ((ObjectNode) f.gcp.mig().path("status").path("versionTarget")).put("isReached", true);
        assertThat(f.pollDone(p.at(6), 6)).isInstanceOf(StepOutcome.Done.class);
    }

    @Test
    void fullEnableRestoresThePolicyKeepsTheFloorAndDeletesNothing() {
        GcpConversionFixture f = new GcpConversionFixture();
        f.planAgainstLive();
        ObjectNode t = f.target(3);
        ((ObjectNode) t.path("customerUpdatePolicy")).put("minimalAction", "RESTART");
        StepProgram p = f.library.program(f.context(OperationKind.ENABLE, t.toString(), null, OperationPhase.FORWARD));
        for (int i = 0; i <= 5; i++) {
            done(f.run(p.at(i), i));
        }
        String spot = f.registry.findByOp(f.opId).orElseThrow().selfLink();
        f.completeRollout(3, spot);
        done(f.pollDone(p.at(6), 6));
        done(f.run(p.at(7), 7));
        done(f.run(p.at(8), 8));
        done(f.run(p.at(9), 9));
        // Two PATCHes: the conversion and the policy restore.
        assertThat(f.mutating.patches).hasSize(2);
        JsonNode restore = f.mutating.patches.get(1);
        assertThat(restore.fieldNames()).toIterable().containsExactly("updatePolicy");
        // PO-GCP-A: with N = 3 the type stays PROACTIVE and maxUnavailable stays 0.
        assertThat(restore.path("updatePolicy").path("minimalAction").asText()).isEqualTo("RESTART");
        assertThat(restore.path("updatePolicy").path("type").asText()).isEqualTo("PROACTIVE");
        assertThat(restore.path("updatePolicy").path("maxUnavailable").path("fixed").asInt()).isZero();
        assertThat(f.mutating.deleted).isEmpty();
        assertThat(f.registry.findByOp(f.opId).orElseThrow().status()).isEqualTo("IN_USE");
    }

    @Test
    void fullSpotRestoresTheWholeCustomerPolicy() {
        GcpConversionFixture f = new GcpConversionFixture();
        f.planAgainstLive();
        StepProgram p = f.library.program(f.context(OperationKind.ENABLE, f.target(0).toString(), null, OperationPhase.FORWARD));
        for (int i = 0; i <= 5; i++) {
            done(f.run(p.at(i), i));
        }
        f.completeRollout(0, f.registry.findByOp(f.opId).orElseThrow().selfLink());
        done(f.pollDone(p.at(6), 6));
        done(f.run(p.at(8), 8));
        assertThat(f.gcp.mig().path("updatePolicy").path("type").asText()).isEqualTo("OPPORTUNISTIC");
        assertThat(f.gcp.mig().path("updatePolicy").path("maxUnavailable").path("fixed").asInt()).isEqualTo(3);
    }

    @Test
    void a409OnPatchIsAConflictNeverSuccess() {
        GcpConversionFixture f = new GcpConversionFixture();
        StepProgram p = enable(f, 3);
        for (int i = 0; i <= 3; i++) {
            done(f.run(p.at(i), i));
        }
        f.mutating.patchStatusToFail = 409;
        f.mutating.patchFailuresLeft = 5;
        StepOutcome o = f.run(p.at(4), 4);
        assertThat(o).isInstanceOfSatisfying(StepOutcome.Failed.class, x -> {
            assertThat(x.kind()).isEqualTo(ErrorKind.CONFLICT);
            assertThat(x.code()).isEqualTo("GCP_MIG_UPDATE_CONFLICT");
        });
        assertThat(f.mutating.patches).as("one attempt, no blind retry on 409").hasSize(1);
    }

    @Test
    void a412WithAnUnchangedMigIsRetriedOnceWithTheNewFingerprint() {
        GcpConversionFixture f = new GcpConversionFixture();
        StepProgram p = enable(f, 3);
        for (int i = 0; i <= 3; i++) {
            done(f.run(p.at(i), i));
        }
        f.mutating.patchStatusToFail = 412;
        f.mutating.patchFailuresLeft = 1;
        done(f.run(p.at(4), 4));
        assertThat(f.mutating.patches).hasSize(2);
        assertThat(f.mutating.requestIds.get(f.mutating.requestIds.size() - 1))
                .isNotEqualTo(f.mutating.requestIds.get(f.mutating.requestIds.size() - 2));
    }

    @Test
    void a412WithAChangedMigIsAConflictAndNeverOverwritesTheCustomersChange() {
        GcpConversionFixture f = new GcpConversionFixture();
        StepProgram p = enable(f, 3);
        for (int i = 0; i <= 3; i++) {
            done(f.run(p.at(i), i));
        }
        f.gcp.mig().put("fingerprint", "someone-else"); // the customer changed the MIG: the step's fresh read sees it...
        f.mutating.patchStatusToFail = 412;
        f.mutating.patchFailuresLeft = 1;
        // ...so make the re-read differ from the first read by changing a managed field after the first read.
        f.mutating.patchFailuresLeft = 0;
        f.gcp.mig().put("fingerprint", "v1");
        GcpMigClient.Mutating racing = new GcpMigClient.Mutating() {
            public JsonNode insertInstanceTemplate(String r, JsonNode b, UUID id) {
                return f.mutating.insertInstanceTemplate(r, b, id);
            }

            public JsonNode deleteInstanceTemplate(String r, String n, UUID id) {
                return f.mutating.deleteInstanceTemplate(r, n, id);
            }

            public JsonNode patchMig(boolean regional, String l, String n, JsonNode b, String fp, UUID id) {
                f.gcp.mig().put("targetSize", 6);
                ((com.fasterxml.jackson.databind.node.ArrayNode) f.gcp.mig().path("versions")).addObject()
                        .put("name", "customer-canary").put("instanceTemplate", GcpConversionFixture.ORIGINAL);
                throw new GcpReadException("instanceGroupManagers.patch", ErrorKind.CONFLICT, 412);
            }
        };
        GcpStepServices racingServices = new GcpStepServices(f.accounts, a -> f.gcp.view(), a -> racing, f.gate,
                new GcpEligibilityEvaluatorStub(f).evaluator, new GcpMigStatusReader(), f.snapshots, f.registry,
                new GcpVersionTargetEvaluator(), f.facts, f.clock, Duration.ofDays(7));
        StepProgram racingProgram = new GcpStepLibrary(racingServices).program(
                f.context(OperationKind.ENABLE, f.target(3).toString(), null, OperationPhase.FORWARD));
        StepOutcome o = f.run(racingProgram.at(4), 4);
        assertThat(o).isInstanceOfSatisfying(StepOutcome.Failed.class, x -> {
            assertThat(x.kind()).isEqualTo(ErrorKind.CONFLICT);
            assertThat(x.code()).isEqualTo("GCP_MIG_UPDATE_CONFLICT");
        });
    }

    @Test
    void aStalePlanFailsStepZeroAndTouchesNothing() {
        GcpConversionFixture f = new GcpConversionFixture();
        f.planAgainstLive();
        f.gcp.mig().put("targetSize", 6);
        ((ObjectNode) f.gcp.mig().path("updatePolicy")).put("minimalAction", "RESTART");
        StepOutcome o = f.run(f.library.program(f.context(OperationKind.ENABLE, f.target(3).toString(), null,
                OperationPhase.FORWARD)).at(0), 0);
        assertThat(o).isInstanceOfSatisfying(StepOutcome.Failed.class, x -> assertThat(x.code()).isEqualTo("PLAN_STALE"));
        assertThat(f.mutating.calls).isEmpty();
    }

    @Test
    void aForeignRolloutIsAConflictThatWaits() {
        GcpConversionFixture f = new GcpConversionFixture();
        StepProgram p = enable(f, 3);
        ((ObjectNode) f.gcp.mig().path("status")).put("isStable", false);
        assertThat(f.run(p.at(0), 0)).isInstanceOfSatisfying(StepOutcome.Failed.class,
                x -> assertThat(x.kind()).isEqualTo(ErrorKind.CONFLICT));
    }

    @Test
    void theGkeNodePoolGuardStillBlocksAtExecution() {
        GcpConversionFixture f = new GcpConversionFixture();
        ((ObjectNode) f.gcp.template().path("properties").path("labels")).put("goog-gke-node", "");
        StepProgram p = enable(f, 3);
        assertThat(f.run(p.at(0), 0)).isInstanceOfSatisfying(StepOutcome.Failed.class,
                x -> assertThat(x.code()).isEqualTo("GCP_GKE_NODE_POOL"));
        assertThat(f.mutating.calls).isEmpty();
    }

    @Test
    void aRegionalMigWithoutZonesFailsBeforeAnyPatch_theZoneCountIsNeverGuessed() {
        GcpConversionFixture f = new GcpConversionFixture();
        StepProgram p = enable(f, 3);
        for (int i = 0; i <= 3; i++) {
            done(f.run(p.at(i), i));
        }
        ((ObjectNode) f.gcp.mig().path("distributionPolicy")).putArray("zones");
        StepOutcome o = f.run(p.at(4), 4);
        assertThat(o).isInstanceOfSatisfying(StepOutcome.Failed.class, x -> assertThat(x.kind()).isEqualTo(ErrorKind.VALIDATION));
        assertThat(f.mutating.patches).isEmpty();
    }

    @Test
    void theGateIsASecondLock_aConversionNeverReachesTheWriteClientWhileItIsClosed() {
        GcpConversionFixture f = new GcpConversionFixture();
        f.gateOpen = false;
        f.wire();
        StepProgram p = enable(f, 3);
        done(f.run(p.at(0), 0));
        done(f.run(p.at(1), 1));
        assertThatThrownBy(() -> f.run(p.at(2), 2)).isInstanceOfSatisfying(AutoSpottingApiException.class,
                e -> assertThat(e.getCode()).isEqualTo("AS_PROVIDER_NOT_READY"));
        assertThat(f.mutating.calls).isEmpty();
    }

    @Test
    void compensationPutsTheOriginalBackSurgeFirstAndRetiresOnlyTheXamOpsTemplate() {
        GcpConversionFixture f = new GcpConversionFixture();
        StepProgram p = enable(f, 3);
        for (int i = 0; i <= 5; i++) {
            done(f.run(p.at(i), i));
        }
        String spot = f.registry.findByOp(f.opId).orElseThrow().selfLink();
        ObjectNode prior = GcpConversionFixture.JSON.createObjectNode();
        prior.put("instanceTemplate", GcpConversionFixture.ORIGINAL);
        prior.putArray("versions").addObject().put("instanceTemplate", GcpConversionFixture.ORIGINAL);
        prior.set("updatePolicy", f.gcp.mig().path("updatePolicy").deepCopy());
        ((ObjectNode) prior.path("updatePolicy")).put("type", "OPPORTUNISTIC");
        ((ObjectNode) prior.path("updatePolicy")).set("maxUnavailable", GcpConversionFixture.JSON.createObjectNode().put("fixed", 3));
        // The customer's own policy before the conversion, as the plan recorded it.
        StepProgram comp = f.library.program(f.context(OperationKind.ENABLE, f.target(3).toString(), prior.toString(),
                OperationPhase.COMPENSATING).kind() == null ? null : new com.xammer.cloud.service.autospotting.spi.OperationContext(
                f.opId, OperationKind.CONFIG_APPLY, f.group(), f.target(3).toString(), prior.toString(),
                OperationPhase.COMPENSATING, f.planId, com.xammer.cloud.domain.autospotting.CompensationTarget.PRIOR_CONFIG));
        assertThat(comp.steps().stream().map(Step::name).toList()).containsExactly("PATCH_MIG", "WAIT_PATCH_OPERATION",
                "WAIT_ROLLOUT", "VERIFY_INSTANCES", "RESTORE_UPDATE_POLICY", "FINALIZE");
        done(f.run(comp.at(0), 0));
        JsonNode body = f.mutating.patches.get(f.mutating.patches.size() - 1);
        assertThat(body.path("versions").path(0).path("instanceTemplate").asText()).isEqualTo(GcpConversionFixture.ORIGINAL);
        assertThat(body.path("updatePolicy").path("maxSurge").path("fixed").asInt()).isEqualTo(3);
        assertThat(body.path("updatePolicy").path("maxUnavailable").path("fixed").asInt()).isZero();
        done(f.run(comp.at(1), 1));
        f.resetInstances(true);
        ((ObjectNode) f.gcp.mig().path("status")).put("isStable", true);
        ((ObjectNode) f.gcp.mig().path("status").path("versionTarget")).put("isReached", true);
        done(f.pollDone(comp.at(2), 2));
        done(f.run(comp.at(3), 3));
        done(f.run(comp.at(4), 4));
        done(f.run(comp.at(5), 5));
        assertThat(f.registry.findByOp(f.opId).orElseThrow().status()).isEqualTo("RETIRED");
        assertThat(f.registry.findByOp(f.opId).orElseThrow().deleteAfter()).isEqualTo(f.clock.instant().plus(Duration.ofDays(7)));
        assertThat(f.mutating.deleted).as("retired, never deleted inline").isEmpty();
        assertThat(spot).contains("/instanceTemplates/");
    }

    @Test
    void anEnableCompensationWithNoSavedOriginalFailsSafelyAndChangesNothing() {
        GcpConversionFixture f = new GcpConversionFixture();
        StepProgram p = f.library.restoreFromSaved(f.context(OperationKind.ENABLE, f.target(3).toString(), null, OperationPhase.COMPENSATING));
        assertThat(p.steps().stream().map(Step::name).toList()).containsExactly("PRIOR_CONFIG_MISSING");
        assertThat(f.run(p.at(0), 0)).isInstanceOf(StepOutcome.Failed.class);
        assertThat(f.mutating.calls).isEmpty();
    }

    @Test
    void anEnableCompensationRestoresTheSavedOriginalNotTheLiveMig() {
        GcpConversionFixture f = new GcpConversionFixture();
        StepProgram p = enable(f, 3);
        done(f.run(p.at(1), 1));
        StepProgram restore = f.library.restoreFromSaved(f.context(OperationKind.ENABLE, f.target(3).toString(), null,
                OperationPhase.COMPENSATING));
        done(f.run(restore.at(0), 0));
        // The live MIG already shows the saved original versions: the PATCH only sets the surge-first policy,
        // aims at the original template and never inserts a template.
        assertThat(f.mutating.inserted).isEmpty();
        assertThat(f.mutating.patches.get(0).path("versions").path(0).path("instanceTemplate").asText())
                .isEqualTo(GcpConversionFixture.ORIGINAL);
    }

    @Test
    void unsupportedKindsAreRefused() {
        GcpConversionFixture f = new GcpConversionFixture();
        assertThatThrownBy(() -> f.library.program(f.context(OperationKind.FALLBACK_ENTER, "{}", null, OperationPhase.FORWARD)))
                .isInstanceOfSatisfying(AutoSpottingApiException.class, e -> assertThat(e.getCode()).isEqualTo("AS_PROVIDER_NOT_READY"));
    }

    /** Builds an evaluator over the fixture's fake Compute. */
    static final class GcpEligibilityEvaluatorStub {
        final GcpEligibilityEvaluator evaluator;

        GcpEligibilityEvaluatorStub(GcpConversionFixture f) {
            this.evaluator = new GcpEligibilityEvaluator(a -> f.gcp.view(), new GcpMachineShapes(),
                    org.mockito.Mockito.mock(com.xammer.cloud.service.autospotting.core.EligibilityCache.class),
                    new com.xammer.cloud.service.autospotting.obs.AutoSpottingMetrics(
                            new io.micrometer.core.instrument.simple.SimpleMeterRegistry()), false, java.time.Clock.systemUTC());
        }
    }
}
