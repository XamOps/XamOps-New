package com.xammer.cloud.service.autospotting.core.lifecycle;

import com.xammer.cloud.domain.autospotting.ActionClass;
import com.xammer.cloud.domain.autospotting.CompensationTarget;
import com.xammer.cloud.domain.autospotting.OperationKind;

/**
 * The compensation target an operation records when it is created (design-core.md §2.2.1, DC-09):
 *
 * <ul>
 *   <li>ENABLE → SNAPSHOT (the snapshot the op persisted);</li>
 *   <li>ADOPT, CONFIG_APPLY, PROFILE_CHANGE → PRIOR_CONFIG (the configuration as read at plan time);</li>
 *   <li>FALLBACK_EXIT → FALLBACK (the fallback configuration);</li>
 *   <li>SAFETY ops (REVERT, FALLBACK_ENTER, ACCEPT_BASELINE, MIG_POLICY_UPDATE) → NONE.</li>
 * </ul>
 *
 * SAFETY ops never compensate toward Spot (§2.2.1): REVERT and FALLBACK_ENTER keep waiting and go
 * FAILED only on a terminal error; ACCEPT_BASELINE is DB-only; MIG_POLICY_UPDATE alerts and ends
 * FAILED without a lifecycle change. {@link OperationStore} reads this when it inserts the op row.
 */
public final class CompensationTargets {

    private CompensationTargets() {
    }

    /** The compensation target for {@code kind} (DC-09, §2.2.1). */
    public static CompensationTarget forKind(OperationKind kind) {
        if (kind == null) {
            throw new IllegalArgumentException("operation kind is required");
        }
        return switch (kind) {
            case ENABLE -> CompensationTarget.SNAPSHOT;
            case ADOPT, CONFIG_APPLY, PROFILE_CHANGE -> CompensationTarget.PRIOR_CONFIG;
            case FALLBACK_EXIT -> CompensationTarget.FALLBACK;
            case REVERT, FALLBACK_ENTER, ACCEPT_BASELINE, MIG_POLICY_UPDATE -> CompensationTarget.NONE;
        };
    }

    /**
     * The action class of {@code kind} (design-core.md §6.2). PROGRESS actions add Spot exposure or
     * change config: enable, adopt, config apply, profile change, accept baseline, MIG policy update,
     * fallback exit. SAFETY actions reduce exposure or restore: revert and fallback enter (and
     * compensation, which is not a distinct kind). This matches the §6.3 matrix, where a FALLBACK_EXIT
     * is gated like a conversion while a REVERT or FALLBACK_ENTER is not.
     */
    public static ActionClass actionClass(OperationKind kind) {
        if (kind == null) {
            throw new IllegalArgumentException("operation kind is required");
        }
        return switch (kind) {
            case ENABLE, ADOPT, CONFIG_APPLY, PROFILE_CHANGE, ACCEPT_BASELINE, MIG_POLICY_UPDATE,
                    FALLBACK_EXIT -> ActionClass.PROGRESS;
            case REVERT, FALLBACK_ENTER -> ActionClass.SAFETY;
        };
    }
}
