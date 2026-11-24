package com.xammer.cloud.service.autospotting.gcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xammer.cloud.domain.autospotting.CompensationTarget;
import com.xammer.cloud.domain.autospotting.OperationKind;
import com.xammer.cloud.domain.autospotting.OperationPhase;
import com.xammer.cloud.domain.autospotting.SpotGroup;
import com.xammer.cloud.service.autospotting.core.AutoSpottingApiException;
import com.xammer.cloud.service.autospotting.gcp.steps.CreateSpotTemplateStep;
import com.xammer.cloud.service.autospotting.gcp.steps.FinalizeStep;
import com.xammer.cloud.service.autospotting.gcp.steps.PatchMigStep;
import com.xammer.cloud.service.autospotting.gcp.steps.PatchPolicyStep;
import com.xammer.cloud.service.autospotting.gcp.steps.PersistSnapshotStep;
import com.xammer.cloud.service.autospotting.gcp.steps.PolicyPrecheckStep;
import com.xammer.cloud.service.autospotting.gcp.steps.PriorConfigMissingStep;
import com.xammer.cloud.service.autospotting.gcp.steps.RestoreUpdatePolicyStep;
import com.xammer.cloud.service.autospotting.gcp.steps.VerifyInstancesStep;
import com.xammer.cloud.service.autospotting.gcp.steps.VerifyPolicyStep;
import com.xammer.cloud.service.autospotting.gcp.steps.VerifyPreconditionsStep;
import com.xammer.cloud.service.autospotting.gcp.steps.WaitPatchOperationStep;
import com.xammer.cloud.service.autospotting.gcp.steps.WaitRolloutStep;
import com.xammer.cloud.service.autospotting.gcp.steps.WaitTemplateOperationStep;
import com.xammer.cloud.service.autospotting.spi.OperationContext;
import com.xammer.cloud.service.autospotting.spi.Step;
import com.xammer.cloud.service.autospotting.spi.StepProgram;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The GCP step library (design-gcp.md §2.6, §3.5): builds the deterministic {@link StepProgram} for an operation
 * from its immutable inputs, so any instance can re-derive it to resume.
 *
 * <pre>
 *  ENABLE / ADOPT          0 VERIFY_PRECONDITIONS  1 PERSIST_SNAPSHOT  2 CREATE_SPOT_TEMPLATE  3 WAIT_TEMPLATE_OPERATION
 *                          4 PATCH_MIG  5 WAIT_PATCH_OPERATION  6 WAIT_ROLLOUT  7 VERIFY_INSTANCES
 *                          8 RESTORE_UPDATE_POLICY  9 FINALIZE
 *  CONFIG_APPLY /          the same without step 1 (the original was saved at enable)
 *  PROFILE_CHANGE          a policy-only CONFIG_APPLY is the MIG_POLICY_UPDATE program (no template, no rollout)
 *  MIG_POLICY_UPDATE       0 VERIFY_PRECONDITIONS  1 PATCH_POLICY  2 WAIT_PATCH_OPERATION  3 VERIFY_POLICY
 *  compensation            4-8 against the prior configuration (PRIOR_CONFIG) or the saved snapshot (an ENABLE),
 *                          then FINALIZE; MIG_POLICY_UPDATE compensates by PATCH_POLICY of the policy read at plan time
 * </pre>
 * Only the REVERT kind and the fallback kinds belong to Phase 28 and are refused here.
 */
@Component
public class GcpStepLibrary {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final GcpStepServices services;

    public GcpStepLibrary(GcpStepServices services) {
        this.services = services;
    }

    /** True for the kinds this library builds a conversion or policy program for. */
    public static boolean supports(OperationKind kind) {
        return kind == OperationKind.ENABLE || kind == OperationKind.ADOPT || kind == OperationKind.CONFIG_APPLY
                || kind == OperationKind.PROFILE_CHANGE || kind == OperationKind.MIG_POLICY_UPDATE;
    }

    /** The program for a forward leg or a PRIOR_CONFIG compensation leg. */
    public StepProgram program(OperationContext op) {
        if (!supports(op.kind())) {
            throw new AutoSpottingApiException(HttpStatus.CONFLICT, "AS_PROVIDER_NOT_READY",
                    "This GCP operation is not available yet.");
        }
        SpotGroup g = op.group();
        boolean compensating = op.phase() == OperationPhase.COMPENSATING;
        if (op.kind() == OperationKind.MIG_POLICY_UPDATE
                || (!compensating && policyOnly(parse(op.targetConfigJson())))) {
            return policyProgram(op, compensating);
        }
        if (compensating) {
            if (op.compensationTarget() == CompensationTarget.SNAPSHOT) {
                throw new IllegalStateException("a SNAPSHOT compensation is built by restore(), not a conversion");
            }
            JsonNode prior = parse(op.compensationConfigJson());
            GcpProgram p = new GcpProgram(op.operationId(), op.kind(), g.getId(), g.getCloudAccountId(), g.getGroupKey(),
                    op.planId(), prior == null ? JSON.createObjectNode() : prior,
                    customerPolicy(g, prior == null ? null : prior.path("updatePolicy")), true);
            return compensationProgram(p, usable(prior));
        }
        JsonNode target = parse(op.targetConfigJson());
        if (target == null || !target.path("versions").isArray() || target.path("versions").isEmpty()) {
            throw new AutoSpottingApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PLAN_TARGET_MISSING",
                    "The plan has no target configuration to apply.");
        }
        JsonNode customer = op.kind() == OperationKind.ENABLE || op.kind() == OperationKind.ADOPT
                ? target.path("customerUpdatePolicy") : customerPolicy(g, target.path("customerUpdatePolicy"));
        GcpProgram p = new GcpProgram(op.operationId(), op.kind(), g.getId(), g.getCloudAccountId(), g.getGroupKey(),
                op.planId(), target, customer, false);
        List<Step> steps = new ArrayList<>();
        steps.add(new VerifyPreconditionsStep(services, p));
        if (op.kind() == OperationKind.ENABLE || op.kind() == OperationKind.ADOPT) {
            steps.add(new PersistSnapshotStep(services, p));
        }
        steps.add(new CreateSpotTemplateStep(services, p));
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
     * The compensation of an ENABLE (target SNAPSHOT): the same surge-first PATCH, aimed at the ORIGINAL
     * configuration this op saved at step 1. The runner's snapshot argument is a fresh read of the live MIG, so it
     * is never used as the restore target; the persisted row is.
     */
    public StepProgram restoreFromSaved(OperationContext op) {
        SpotGroup g = op.group();
        Optional<GcpSnapshotStore.Stored> saved = services.snapshots().findByOp(op.operationId());
        if (saved.isEmpty()) {
            saved = services.snapshots().findLinked(g.getId());
        }
        JsonNode prior = saved.map(s -> parse(s.payload())).orElse(null);
        GcpProgram p = new GcpProgram(op.operationId(), op.kind(), g.getId(), g.getCloudAccountId(), g.getGroupKey(),
                op.planId(), prior == null ? JSON.createObjectNode() : prior,
                prior == null ? null : prior.path("updatePolicy"), true);
        return compensationProgram(p, usable(prior));
    }

    private StepProgram compensationProgram(GcpProgram p, boolean hasPrior) {
        if (!hasPrior) {
            return new StepProgram(List.of(new PriorConfigMissingStep(services, p)));
        }
        return new StepProgram(List.of(new PatchMigStep(services, p), new WaitPatchOperationStep(services, p),
                new WaitRolloutStep(services, p), new VerifyInstancesStep(services, p),
                new RestoreUpdatePolicyStep(services, p), new FinalizeStep(services, p)));
    }

    private StepProgram policyProgram(OperationContext op, boolean compensating) {
        SpotGroup g = op.group();
        JsonNode target = parse(op.targetConfigJson());
        ObjectNode shaped = JSON.createObjectNode();
        shaped.put("policyOnly", true);
        if (compensating) {
            JsonNode prior = target == null ? null : target.path("prior");
            if (prior == null || !prior.isObject() || prior.isEmpty()) {
                JsonNode comp = parse(op.compensationConfigJson());
                prior = comp != null && comp.path("updatePolicy").isObject()
                        ? JSON.createObjectNode().set("updatePolicy", comp.path("updatePolicy").deepCopy()) : null;
            }
            GcpProgram p = new GcpProgram(op.operationId(), op.kind(), g.getId(), g.getCloudAccountId(), g.getGroupKey(),
                    op.planId(), shaped, null, true);
            if (prior == null || prior.isEmpty()) {
                return new StepProgram(List.of(new PriorConfigMissingStep(services, p)));
            }
            shaped.set("edit", prior);
            return new StepProgram(List.of(new PatchPolicyStep(services, p), new VerifyPolicyStep(services, p, false),
                    new VerifyPolicyStep(services, p, true)));
        }
        if (target == null || !target.path("edit").isObject() || target.path("edit").isEmpty()) {
            throw new AutoSpottingApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PLAN_TARGET_MISSING",
                    "The plan has no policy change to apply.");
        }
        shaped.set("edit", target.path("edit"));
        GcpProgram p = new GcpProgram(op.operationId(), op.kind(), g.getId(), g.getCloudAccountId(), g.getGroupKey(),
                op.planId(), shaped, null, false);
        return new StepProgram(List.of(new PolicyPrecheckStep(services, p), new PatchPolicyStep(services, p),
                new VerifyPolicyStep(services, p, false), new VerifyPolicyStep(services, p, true)));
    }

    private static boolean policyOnly(JsonNode target) {
        return target != null && target.path("policyOnly").asBoolean(false);
    }

    /** The customer's own policy: the one the group kept in desired_config, else {@code fallback}. */
    private static JsonNode customerPolicy(SpotGroup g, JsonNode fallback) {
        JsonNode desired = parse(g.getDesiredConfig());
        if (desired != null && desired.path("customerUpdatePolicy").isObject()) {
            return desired.path("customerUpdatePolicy");
        }
        return fallback;
    }

    private static boolean usable(JsonNode prior) {
        return prior != null && (prior.path("versions").isArray() && !prior.path("versions").isEmpty()
                || prior.hasNonNull("instanceTemplate"));
    }

    static JsonNode parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSON.readTree(json);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
