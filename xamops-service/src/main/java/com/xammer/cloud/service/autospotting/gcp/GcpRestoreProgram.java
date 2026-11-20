package com.xammer.cloud.service.autospotting.gcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.autospotting.OperationKind;
import com.xammer.cloud.domain.autospotting.SpotGroup;
import com.xammer.cloud.service.autospotting.gcp.steps.FinalizeStep;
import com.xammer.cloud.service.autospotting.gcp.steps.PatchMigStep;
import com.xammer.cloud.service.autospotting.gcp.steps.RestoreUpdatePolicyStep;
import com.xammer.cloud.service.autospotting.gcp.steps.ResolveRevertTargetStep;
import com.xammer.cloud.service.autospotting.gcp.steps.RevertSnapshotMissingStep;
import com.xammer.cloud.service.autospotting.gcp.steps.VerifyInstancesStep;
import com.xammer.cloud.service.autospotting.gcp.steps.VerifyRevertPreconditionsStep;
import com.xammer.cloud.service.autospotting.gcp.steps.WaitPatchOperationStep;
import com.xammer.cloud.service.autospotting.gcp.steps.WaitRolloutStep;
import com.xammer.cloud.service.autospotting.gcp.steps.WaitTemplateOperationStep;
import com.xammer.cloud.service.autospotting.spi.EligibilityResult;
import com.xammer.cloud.service.autospotting.spi.EligibilityResult.Check;
import com.xammer.cloud.service.autospotting.spi.OperationContext;
import com.xammer.cloud.service.autospotting.spi.PlanBody;
import com.xammer.cloud.service.autospotting.spi.PlanRequest;
import com.xammer.cloud.service.autospotting.spi.Step;
import com.xammer.cloud.service.autospotting.spi.StepProgram;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The GCP REVERT program and plan (design-gcp.md §2.6 "restore(REVERT)", FR-REV-01 to FR-REV-05, DR-03, GR-03).
 *
 * <pre>
 *  REVERT   0 VERIFY_PRECONDITIONS (revert set only)  1 RESOLVE_REVERT_TARGET  2 WAIT_TEMPLATE_OPERATION
 *           3 PATCH_MIG (instanceTemplate / versions[] / instanceFlexibilityPolicy, XamOps surge-first policy)
 *           4 WAIT_PATCH_OPERATION  5 WAIT_ROLLOUT  6 VERIFY_INSTANCES (the §4.4 success test against the snapshot target)
 *           7 RESTORE_UPDATE_POLICY (the snapshot's)  8 FINALIZE (the Spot template is retired, never deleted)
 * </pre>
 * The program restores the snapshot the group is linked to, never the runner's fresh live read. It is built only
 * while {@link GcpProviderGate} is open. A revert is never blocked by a conversion-only check; a missing restore
 * permission makes the plan carry {@code REVERT_NEEDS_PERMISSION} (confirm refuses it with 409, retryable) and a
 * step 0 that finds it missing ends the op FAILED before any mutation, so the group keeps its prior state.
 */
@Component
public class GcpRestoreProgram {
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    /** The permissions every revert uses; {@code compute.instanceTemplates.create} is added when a template is rebuilt. */
    public static final List<String> REVERT_PERMISSIONS = List.of("compute.instanceGroupManagers.update",
            "compute.instanceTemplates.get");
    public static final String CREATE_PERMISSION = "compute.instanceTemplates.create";
    public static final String NEEDS_PERMISSION = "REVERT_NEEDS_PERMISSION";
    public static final String ROLE = "xamopsAutoSpotting";

    private final GcpStepServices services;

    public GcpRestoreProgram(GcpStepServices services) {
        this.services = services;
    }

    /** The REVERT program of {@code op}. */
    public StepProgram program(OperationContext op) {
        SpotGroup g = op.group();
        Optional<GcpSnapshotStore.Stored> saved = services.snapshots().findLinked(g.getId());
        if (saved.isEmpty()) {
            saved = services.snapshots().findByOp(op.operationId());
        }
        JsonNode snap = saved.map(s -> parse(s.payload())).orElse(null);
        GcpProgram p = new GcpProgram(op.operationId(), OperationKind.REVERT, g.getId(), g.getCloudAccountId(),
                g.getGroupKey(), op.planId(), snap == null ? JSON.createObjectNode() : snap,
                snap == null ? null : snap.path("updatePolicy"), true);
        if (snap == null || GcpRevertTarget.snapshotVersions(snap).isEmpty()) {
            return new StepProgram(List.of(new RevertSnapshotMissingStep(services, p)));
        }
        List<Step> steps = new ArrayList<>();
        steps.add(new VerifyRevertPreconditionsStep(services, p));
        steps.add(new ResolveRevertTargetStep(services, p));
        steps.add(new WaitTemplateOperationStep(services, p));
        steps.add(new PatchMigStep(services, p));
        steps.add(new WaitPatchOperationStep(services, p));
        steps.add(new WaitRolloutStep(services, p));
        steps.add(new VerifyInstancesStep(services, p));
        steps.add(new RestoreUpdatePolicyStep(services, p));
        steps.add(new FinalizeStep(services, p));
        return new StepProgram(steps);
    }

    /**
     * The REVERT plan, read only: the three-way result, the permission pre-check and what will be rebuilt. It never
     * runs the conversion eligibility checks (DR-03). A missing permission is a blocker coded
     * {@code REVERT_NEEDS_PERMISSION}; a failed permission read is a {@code CHECK_FAILED} that step 0 repeats.
     */
    public PlanBody plan(PlanRequest req, CloudAccount account, GcpReadOnlyView view) {
        GcpEligibilityEvaluator.Key key = GcpEligibilityEvaluator.split(req.ref().groupKey());
        Optional<JsonNode> raw;
        try {
            raw = services.statusReader().describeGroup(view, key);
        } catch (GcpReadException e) {
            return blocked(Check.failed("b-CHECK_FAILED-MIG_READ", "Could not read the instance group.", e.kind()));
        }
        if (raw.isEmpty()) {
            return blocked(Check.of("b-GCP_MIG_NOT_FOUND", "GCP_MIG_NOT_FOUND", "The instance group no longer exists."));
        }
        GcpMigFacts g = GcpMigFacts.from(key.project(), raw.get());
        Optional<GcpSnapshotStore.Stored> saved = services.snapshots().findLinkedByKey(account.getId(), req.ref().groupKey());
        JsonNode snap = saved.map(s -> parse(s.payload())).orElse(null);
        if (snap == null || GcpRevertTarget.snapshotVersions(snap).isEmpty()) {
            return blocked(Check.of("b-REVERT_SNAPSHOT_MISSING", "REVERT_SNAPSHOT_MISSING",
                    "The original configuration was not saved for this group, so it cannot be restored."));
        }
        GcpProgram p = new GcpProgram(new UUID(0L, 0L), OperationKind.REVERT, saved.get().groupId(), account.getId(),
                req.ref().groupKey(), null, snap, snap.path("updatePolicy"), true);
        GcpRevertTarget.Resolved r;
        try {
            r = GcpRevertTarget.resolve(services, p, view, g);
        } catch (GcpReadException e) {
            return blocked(Check.failed("b-CHECK_FAILED-TEMPLATE_READ", "Could not read the original template.", e.kind()));
        }
        if (r.failure() != null) {
            return blocked(Check.of("b-" + r.failure(), r.failure(),
                    "The original configuration cannot be restored from what was saved."));
        }
        List<Check> blockers = new ArrayList<>();
        List<String> required = new ArrayList<>(REVERT_PERMISSIONS);
        if (r.rebuild()) {
            required.add(CREATE_PERMISSION);
        }
        List<String> missing = new ArrayList<>();
        try {
            Set<String> granted = view.reads().testIamPermissions(required);
            required.stream().filter(x -> !granted.contains(x)).forEach(missing::add);
        } catch (GcpReadException e) {
            blockers.add(Check.failed("b-CHECK_FAILED-PERMISSIONS", "Could not check the permissions for the revert.", e.kind()));
        }
        if (!missing.isEmpty()) {
            blockers.add(Check.of("b-" + NEEDS_PERMISSION, NEEDS_PERMISSION, "The connection is missing "
                    + String.join(", ", missing) + ". Grant the " + ROLE + " role, then plan the revert again."));
        }
        List<Check> warnings = new ArrayList<>();
        for (String note : r.notes()) {
            warnings.add(Check.of(note, note, noteText(note)));
        }
        if (!saved.get().exact() && r.notes().stream().noneMatch(GcpRevertTarget.NOTE_SNAPSHOT_INEXACT::equals)) {
            warnings.add(Check.of(GcpRevertTarget.NOTE_SNAPSHOT_INEXACT, GcpRevertTarget.NOTE_SNAPSHOT_INEXACT,
                    "The saved original configuration is not complete, so the restore is the closest match."));
        }
        EligibilityResult eligibility = new EligibilityResult(blockers, warnings, List.of());
        ObjectNode current = JSON.createObjectNode();
        current.put("shape", "MIG");
        current.put("instanceTemplate", g.instanceTemplate());
        current.set("versions", g.raw().path("versions").deepCopy());
        current.set("updatePolicy", g.updatePolicy().deepCopy());
        if (g.instanceFlexibilityPolicy().isObject()) {
            current.set("instanceFlexibilityPolicy", g.instanceFlexibilityPolicy().deepCopy());
        }
        ObjectNode target = JSON.createObjectNode();
        target.put("shape", "MIG");
        target.set("versions", r.versions().deepCopy());
        if (snap.path("updatePolicy").isObject()) {
            target.set("updatePolicy", snap.path("updatePolicy").deepCopy());
        }
        if (r.flexibility() != null) {
            target.set("instanceFlexibilityPolicy", r.flexibility().deepCopy());
        }
        target.put("exact", r.exact());
        target.put("rebuiltTemplate", r.rebuild());
        target.put("removesSpotVersion", true);
        target.put("removesAttributionLabel", true); // PO-20: xamops-autospotting=<groupId> goes with the Spot templates
        ArrayNode diff = JSON.createArrayNode();
        diff.addObject().put("field", "versions").put("from", String.valueOf(g.versions().size()))
                .put("to", String.valueOf(r.versions().size()));
        diff.addObject().put("field", "spotVersion").put("from", GcpMigFacts.SPOT_VERSION).putNull("to");
        int surge = g.regional() ? Math.max(1, g.zoneCount()) : 1;
        PlanBody.Rollout rollout = new PlanBody.Rollout("SURGE_FIRST_PROACTIVE_REPLACE", g.targetSize(), surge,
                "5 min to 1 h", surge + " extra VM(s) above the target size during the rollout (a warning, not a blocker)",
                write(JSON.createObjectNode().put("surgeVms", surge)));
        ObjectNode path = JSON.createObjectNode().put("program", "REVERT").put("method", "RESTORE_SNAPSHOT")
                .put("exact", r.exact());
        ArrayNode needs = path.putArray("needsPermission");
        missing.forEach(needs::add);
        if (!missing.isEmpty()) {
            path.put("code", NEEDS_PERMISSION).put("roleToGrant", ROLE);
        }
        return new PlanBody(write(current), write(target), write(diff), null, eligibility, rollout, List.of(), null, null,
                write(path));
    }

    private static PlanBody blocked(Check c) {
        return new PlanBody(null, null, null, null, new EligibilityResult(List.of(c), List.of(), List.of()), null,
                List.of(), null, null, null);
    }

    private static String noteText(String note) {
        return switch (note) {
            case GcpRevertTarget.NOTE_REBUILT -> "The original instance template was deleted. XamOps will create a copy of "
                    + "it from the saved configuration and use that; it is not the original object.";
            case GcpRevertTarget.NOTE_KEPT_CUSTOMER -> "The template was changed outside XamOps after enabling, or accepted as "
                    + "the baseline. It is kept; only the Spot version is removed.";
            default -> note;
        };
    }

    private static JsonNode parse(String json) {
        try {
            return json == null || json.isBlank() ? null : JSON.readTree(json);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static String write(JsonNode n) {
        try {
            return JSON.writeValueAsString(JSON.treeToValue(n, Object.class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("plan serialization failed", e);
        }
    }
}
