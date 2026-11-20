package com.xammer.cloud.dto.autospotting.v2;

import com.xammer.cloud.dto.autospotting.v2.validation.AsgName;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * The body of {@code POST /api/autospotting/v2/accounts/{accountId}/plans} (design-core.md §7.1,
 * §11; plan-phase-11.md task 11.5). Identifies the plan kind and the group — by an existing
 * {@code spot_group.id}, or by a validated {@code groupRef} ({@code region/asgName} for AWS;
 * {@code project/location/migName} for GCP) for a group with no row yet — plus the chosen profile.
 * An {@code Idempotency-Key} header is required on the POST (checked by the controller, §3.7).
 *
 * @param kind      the plan kind: ENABLE | ADOPT | CONFIG_APPLY | PROFILE_CHANGE | REVERT |
 *                  ACCEPT_BASELINE | MIG_POLICY_UPDATE (validated against the group's state server-side)
 * @param groupId   an existing group's {@code spot_group.id} (mutually exclusive with {@code groupRef})
 * @param groupRef  the provider group key for a group with no row ({@code region/asgName}, etc.)
 * @param profileId the chosen profile id (conservative preselected, PO-18)
 * @param policyEdit GCP only: the edited rolling-update fields of a MIG_POLICY_UPDATE or policy-only CONFIG_APPLY
 *                   ({@code {"updatePolicy":{...},"targetSize":n}}, edited fields only), else null
 */
public record PlanRequest(
        @NotNull @Size(min = 1, max = 24) String kind,
        UUID groupId,
        @AsgName String groupRef,
        @Size(max = 64) String profileId,
        java.util.Map<String, Object> policyEdit) {

    /** The request without a policy edit (every kind except a GCP update-policy change). */
    public PlanRequest(String kind, UUID groupId, String groupRef, String profileId) {
        this(kind, groupId, groupRef, profileId, null);
    }
}
