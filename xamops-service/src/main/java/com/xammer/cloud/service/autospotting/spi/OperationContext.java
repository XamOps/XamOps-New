package com.xammer.cloud.service.autospotting.spi;

import com.xammer.cloud.domain.autospotting.CompensationTarget;
import com.xammer.cloud.domain.autospotting.OperationKind;
import com.xammer.cloud.domain.autospotting.OperationPhase;
import com.xammer.cloud.domain.autospotting.SpotGroup;

/**
 * What a provider's {@code executeConversion} or {@code restore} needs to build its
 * {@link StepProgram} (design-core.md §9.1). Cloud-neutral: the operation id and kind, the group
 * row, the confirmed target configuration (ENABLE/ADOPT/CONFIG_APPLY/PROFILE_CHANGE/
 * MIG_POLICY_UPDATE) and the compensation configuration to restore for a PRIOR_CONFIG / FALLBACK
 * target (§2.2.1). The program is derived from these immutable inputs, so it can be re-derived to
 * resume on any instance (§9.1).
 *
 * <p>Minimal by design: the full runner context — timeouts, switch state, lease handle — is assembled
 * by the runner in a later phase (plan-phase-07+). The lease handle a step uses is passed per step
 * via {@link StepContext}. This type exists so {@link SpotProvider} compiles as the cloud-neutral
 * boundary this phase defines.
 */
public record OperationContext(
        java.util.UUID operationId,
        OperationKind kind,
        SpotGroup group,
        String targetConfigJson,
        String compensationConfigJson,
        OperationPhase phase,
        java.util.UUID planId,
        CompensationTarget compensationTarget) {

    /** The five-argument form: a forward-leg context with no plan and no compensation target. */
    public OperationContext(java.util.UUID operationId, OperationKind kind, SpotGroup group,
                            String targetConfigJson, String compensationConfigJson) {
        this(operationId, kind, group, targetConfigJson, compensationConfigJson, OperationPhase.FORWARD, null, null);
    }

    public OperationContext {
        if (operationId == null) {
            throw new IllegalArgumentException("operationId is required");
        }
        if (kind == null) {
            throw new IllegalArgumentException("operation kind is required");
        }
        phase = phase == null ? OperationPhase.FORWARD : phase;
    }
}
