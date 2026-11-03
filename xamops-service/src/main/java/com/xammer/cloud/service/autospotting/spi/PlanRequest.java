package com.xammer.cloud.service.autospotting.spi;

import com.xammer.cloud.domain.autospotting.OperationKind;

/**
 * The input to a provider's {@code createPlan} (design-core.md §9.1). Cloud-neutral: the group the
 * plan is for, the plan kind, and the chosen {@link Profile}. {@code createPlan} fills every
 * {@link PlanBody} field and computes the live fingerprint, deterministically for the same inputs,
 * with no side effects (§9.2).
 *
 * <p>Minimal by design (like {@link Profile}): the full plan-request model — idempotency key, actor,
 * acknowledgments — arrives with plan creation in a later phase (plan-phase-07+). This type exists so
 * {@link SpotProvider} compiles as the cloud-neutral boundary this phase defines.
 */
public record PlanRequest(GroupRef ref, OperationKind kind, Profile profile) {

    public PlanRequest {
        if (ref == null) {
            throw new IllegalArgumentException("group ref is required");
        }
        if (kind == null) {
            throw new IllegalArgumentException("plan kind is required");
        }
    }
}
