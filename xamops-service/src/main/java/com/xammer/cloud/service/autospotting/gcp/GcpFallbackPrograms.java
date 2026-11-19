package com.xammer.cloud.service.autospotting.gcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xammer.cloud.domain.autospotting.CompensationTarget;
import com.xammer.cloud.domain.autospotting.OperationKind;
import com.xammer.cloud.domain.autospotting.OperationPhase;
import com.xammer.cloud.domain.autospotting.SpotGroup;
import com.xammer.cloud.service.autospotting.core.AutoSpottingApiException;
import com.xammer.cloud.service.autospotting.core.FallbackProperties;
import com.xammer.cloud.service.autospotting.gcp.steps.FinalizeStep;
import com.xammer.cloud.service.autospotting.gcp.steps.PatchMigStep;
import com.xammer.cloud.service.autospotting.gcp.steps.RestoreUpdatePolicyStep;
import com.xammer.cloud.service.autospotting.gcp.steps.VerifyFallbackAppliedStep;
import com.xammer.cloud.service.autospotting.gcp.steps.VerifyFallbackPreconditionsStep;
import com.xammer.cloud.service.autospotting.gcp.steps.VerifyInstancesStep;
import com.xammer.cloud.service.autospotting.gcp.steps.WaitCapacityStep;
import com.xammer.cloud.service.autospotting.gcp.steps.WaitPatchOperationStep;
import com.xammer.cloud.service.autospotting.gcp.steps.WaitRolloutStep;
import com.xammer.cloud.service.autospotting.spi.OperationContext;
import com.xammer.cloud.service.autospotting.spi.StepProgram;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The GCP fallback programs (design-gcp.md §7.3, DC-07, DC-12), derived from the operation's immutable inputs like the
 * conversion programs, so any instance can resume them.
 *
 * <pre>
 *  FALLBACK_ENTER   0 VERIFY_PRECONDITIONS  1 PATCH_MIG (a single xas-standard version on the original template,
 *                   surge-first)  2 WAIT_PATCH_OPERATION  3 VERIFY_FALLBACK_APPLIED  4 WAIT_CAPACITY (the tail wait,
 *                   bounded by tail-wait-max)  5 RESTORE_UPDATE_POLICY (skipped when the wait ended CAPACITY_PENDING)
 *  FALLBACK_EXIT    the ENABLE program from step 4 with desired_config and the existing Spot template:
 *                   PATCH_MIG, WAIT_PATCH_OPERATION, WAIT_ROLLOUT, VERIFY_INSTANCES, RESTORE_UPDATE_POLICY, FINALIZE
 *  FALLBACK_EXIT compensation (target FALLBACK): the FALLBACK_ENTER PATCH and its waits, no template retired
 * </pre>
 * Fallback is SAFETY: it runs while conversions are paused and under the account switch, and only the freeze stops
 * it. It is still built only while {@link GcpProviderGate} is open (the provider checks), so with the GCP flags false
 * nothing here reaches GCP. No template is created or retired by a fallback: the Spot copy is kept for the exit.
 */
@Component
public class GcpFallbackPrograms {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final GcpStepServices services;
    private final FallbackProperties props;

    public GcpFallbackPrograms(GcpStepServices services, FallbackProperties props) {
        this.services = services;
        this.props = props.forProvider(com.xammer.cloud.domain.autospotting.Provider.GCP);
    }

    /** True for the kinds this class builds a program for. */
    public static boolean supports(OperationKind kind) {
        return kind == OperationKind.FALLBACK_ENTER || kind == OperationKind.FALLBACK_EXIT;
    }

    public StepProgram program(OperationContext op) {
        if (!supports(op.kind())) {
            throw new IllegalArgumentException("not a fallback kind: " + op.kind());
        }
        SpotGroup g = op.group();
        boolean compensating = op.phase() == OperationPhase.COMPENSATING;
        JsonNode desired = parse(g.getDesiredConfig());
        JsonNode customer = desired != null && desired.path("customerUpdatePolicy").isObject()
                ? desired.path("customerUpdatePolicy") : null;
        if (op.kind() == OperationKind.FALLBACK_ENTER) {
            if (compensating) {
                throw new IllegalStateException("a FALLBACK_ENTER never compensates toward Spot");
            }
            GcpProgram p = program(op, JSON.createObjectNode(), customer, false);
            return new StepProgram(List.of(new VerifyFallbackPreconditionsStep(services, p), new PatchMigStep(services, p),
                    new WaitPatchOperationStep(services, p), new VerifyFallbackAppliedStep(services, p),
                    new WaitCapacityStep(services, p, props), new RestoreUpdatePolicyStep(services, p)));
        }
        if (compensating) {
            if (op.compensationTarget() != CompensationTarget.FALLBACK) {
                throw new IllegalStateException("a FALLBACK_EXIT compensates to FALLBACK only");
            }
            GcpProgram p = program(op, JSON.createObjectNode(), customer, true);
            return new StepProgram(List.of(new PatchMigStep(services, p), new WaitPatchOperationStep(services, p),
                    new WaitRolloutStep(services, p), new VerifyInstancesStep(services, p),
                    new RestoreUpdatePolicyStep(services, p)));
        }
        JsonNode config = parse(op.targetConfigJson());
        if (config == null || !config.path("versions").isArray() || config.path("versions").isEmpty()) {
            throw new AutoSpottingApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PLAN_TARGET_MISSING",
                    "The group has no desired configuration to return to.");
        }
        JsonNode exitCustomer = config.path("customerUpdatePolicy").isObject() ? config.path("customerUpdatePolicy") : customer;
        GcpProgram p = program(op, config, exitCustomer, false);
        return new StepProgram(List.of(new PatchMigStep(services, p), new WaitPatchOperationStep(services, p),
                new WaitRolloutStep(services, p), new VerifyInstancesStep(services, p),
                new RestoreUpdatePolicyStep(services, p), new FinalizeStep(services, p)));
    }

    private static GcpProgram program(OperationContext op, JsonNode target, JsonNode customer, boolean compensation) {
        SpotGroup g = op.group();
        return new GcpProgram(op.operationId(), op.kind(), g.getId(), g.getCloudAccountId(), g.getGroupKey(),
                op.planId(), target, customer, compensation);
    }

    private static JsonNode parse(String json) {
        return GcpStepLibrary.parse(json);
    }
}
