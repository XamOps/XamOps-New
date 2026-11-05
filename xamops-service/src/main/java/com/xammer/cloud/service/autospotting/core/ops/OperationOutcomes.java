package com.xammer.cloud.service.autospotting.core.ops;

import com.xammer.cloud.domain.autospotting.CompensationTarget;
import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.OperationKind;
import com.xammer.cloud.domain.autospotting.SpotOperation;

/**
 * The lifecycle target a finished operation leads to (design-core.md §2.2, §2.2.1, §4.6, §4.7). Pure
 * mapping, no I/O, so the {@link OperationRunner} and {@link StartupRecovery} share one source of
 * truth for "which state does this op end in". It encodes the §2.2.1 table (op kind + compensation
 * target → group path) and the §4.7 preemption outcomes.
 *
 * <p>This is the <em>intended</em> target; {@link com.xammer.cloud.service.autospotting.core.lifecycle.GroupLifecycleService}
 * still guards every transition against the §2.2 edge table and the row's {@code (state, version)},
 * so an impossible edge is refused, not written.
 */
public final class OperationOutcomes {

    private OperationOutcomes() {
    }

    /**
     * The group state a FORWARD op leads to on success (§2.2.1 "Group path on success").
     *
     * @param kind       the op kind
     * @param priorState the op's {@code prior_state} (used where the forward target is the prior
     *                   state, e.g. a drift re-apply back to PAUSED)
     */
    public static GroupState forwardSuccess(OperationKind kind, GroupState priorState) {
        return switch (kind) {
            case ENABLE -> GroupState.ACTIVE;
            case ADOPT -> GroupState.LEGACY_UNCONFIRMED;
            case CONFIG_APPLY, PROFILE_CHANGE ->
                    // From ACTIVE → ACTIVE; a drift re-apply from PAUSED(DRIFT) → PAUSED (§2.2.1).
                    priorState == GroupState.PAUSED ? GroupState.PAUSED : GroupState.ACTIVE;
            case FALLBACK_EXIT -> GroupState.ACTIVE;
            case FALLBACK_ENTER -> GroupState.FALLBACK;
            case REVERT -> GroupState.REVERTED;
            // ACCEPT_BASELINE / MIG_POLICY_UPDATE make no lifecycle change (§2.2.1); null signals that.
            case ACCEPT_BASELINE, MIG_POLICY_UPDATE -> null;
        };
    }

    /**
     * The group state a COMPENSATING op leads to on successful compensation (§2.2.1 "Group path on
     * success" of the compensation leg). For SNAPSHOT it is REVERTED; for PRIOR_CONFIG and FALLBACK
     * it is the op's {@code prior_state} (what the op compensates back to).
     */
    public static GroupState compensationSuccess(SpotOperation op) {
        CompensationTarget target = op.getCompensationTarget();
        return switch (target) {
            case SNAPSHOT -> GroupState.REVERTED;
            case PRIOR_CONFIG, FALLBACK -> op.getPriorState();
            case NONE -> null; // SAFETY ops never compensate toward Spot (§2.2.1)
        };
    }

    /**
     * The state a terminally-failed compensation leads to (§2.2.1 "On compensation failure"):
     * ENABLE's snapshot restore that fails ends REVERTING → FAILED; every other compensation failure
     * ends FAILED from APPLYING. Either way the lifecycle target is FAILED.
     */
    public static GroupState compensationFailure() {
        return GroupState.FAILED;
    }
}
