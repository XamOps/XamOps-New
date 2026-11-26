package com.xammer.cloud.service.autospotting.core.plan;

import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.OperationKind;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The plan-kind-by-state table of design-core.md §7.4 (plan-phase-11.md task 11.2). Plan creation
 * checks the kind against the group's state before it reads the cloud; confirm repeats the check
 * under the row lock. A kind not allowed for the state is 409 {@code PLAN_KIND_NOT_ALLOWED} whose
 * {@code detail} names the state and the kinds that state allows.
 *
 * <p>The REVERT exceptions of §7.4 (a group XamOps doesn't manage → {@code NOT_MANAGED}; a REVERTING
 * group → {@code AS_GROUP_BUSY}) are decided by the caller before this table, because they map to
 * different codes. A group with no {@code spot_group} row is represented by a {@code null} state;
 * ENABLE and MIG_POLICY_UPDATE are allowed then (a row appears with the first plan, §2.1).
 */
public final class PlanKinds {

    // The allowed group states per plan kind (§7.4). A null state (no row) is handled separately.
    private static final Map<OperationKind, Set<GroupState>> ALLOWED = build();
    // The plan kinds this phase accepts at the plan endpoints (§7.4 table rows). System kinds
    // (FALLBACK_*) are never plan kinds.
    private static final Set<OperationKind> PLAN_KINDS = EnumSet.of(
            OperationKind.ENABLE, OperationKind.ADOPT, OperationKind.CONFIG_APPLY,
            OperationKind.PROFILE_CHANGE, OperationKind.REVERT, OperationKind.ACCEPT_BASELINE,
            OperationKind.MIG_POLICY_UPDATE);

    private PlanKinds() {
    }

    /** Whether {@code kind} is a plan kind (not a system FALLBACK_* op). */
    public static boolean isPlanKind(OperationKind kind) {
        return kind != null && PLAN_KINDS.contains(kind);
    }

    /**
     * Whether a plan of {@code kind} is allowed for a group in {@code state} ({@code null} = no row).
     * ENABLE from a PLANNED group is a superseding plan (§7.4). Does not apply the REVERT
     * NOT_MANAGED/AS_GROUP_BUSY exceptions — the caller resolves those first.
     */
    public static boolean isAllowed(OperationKind kind, GroupState state) {
        if (!isPlanKind(kind)) {
            return false;
        }
        if (state == null) {
            // No row: ENABLE (a new enable) and MIG_POLICY_UPDATE (GCP, inserts an UNMANAGED row).
            return kind == OperationKind.ENABLE || kind == OperationKind.MIG_POLICY_UPDATE;
        }
        return ALLOWED.getOrDefault(kind, EnumSet.noneOf(GroupState.class)).contains(state);
    }

    /**
     * {@link #isAllowed(OperationKind, GroupState)} plus the GR-02 row (design-aws.md §5.3): a REVERT is allowed
     * from REVERTED only after a drain-only revert that left XamOps' configuration on the group (PASSROLE_DENIED or
     * CHECK_FAILED, {@link RevertHistory#revertAllowedAgain}). Every other REVERTED group still has nothing to revert
     * ({@code NOT_MANAGED}).
     *
     * @param regrant whether {@link RevertHistory#revertAllowedAgain} re-grants the revert for this group
     */
    public static boolean isAllowed(OperationKind kind, GroupState state, boolean regrant) {
        if (kind == OperationKind.REVERT && state == GroupState.REVERTED) {
            return regrant;
        }
        return isAllowed(kind, state);
    }

    /** The plan kinds a group in {@code state} ({@code null} = no row) allows, for the error detail. */
    public static Set<OperationKind> kindsAllowedIn(GroupState state) {
        EnumSet<OperationKind> kinds = EnumSet.noneOf(OperationKind.class);
        for (OperationKind kind : PLAN_KINDS) {
            if (isAllowed(kind, state)) {
                kinds.add(kind);
            }
        }
        return kinds;
    }

    /** The §7.4 {@code detail}: the group's state and the kinds it allows. */
    public static String notAllowedDetail(OperationKind kind, GroupState state) {
        Set<OperationKind> allowed = kindsAllowedIn(state);
        String stateName = state == null ? "no managed row" : state.name();
        return kind.name() + " is not available when the group is " + stateName
                + " (allowed: " + (allowed.isEmpty() ? "none" : allowed) + ").";
    }

    private static Map<OperationKind, Set<GroupState>> build() {
        Map<OperationKind, Set<GroupState>> m = new EnumMap<>(OperationKind.class);

        // ENABLE: No row (handled above), UNMANAGED, REVERTED; PLANNED with an OPEN plan (superseding).
        m.put(OperationKind.ENABLE, EnumSet.of(
                GroupState.UNMANAGED, GroupState.REVERTED, GroupState.PLANNED));

        // ADOPT: LEGACY_UNCONFIRMED (except reason ORPHAN_MARKER, decided by the caller).
        m.put(OperationKind.ADOPT, EnumSet.of(GroupState.LEGACY_UNCONFIRMED));

        // CONFIG_APPLY: ACTIVE, and PAUSED (DRIFT) as a re-apply.
        m.put(OperationKind.CONFIG_APPLY, EnumSet.of(GroupState.ACTIVE, GroupState.PAUSED));

        // PROFILE_CHANGE: ACTIVE.
        m.put(OperationKind.PROFILE_CHANGE, EnumSet.of(GroupState.ACTIVE));

        // ACCEPT_BASELINE: PAUSED (DRIFT).
        m.put(OperationKind.ACCEPT_BASELINE, EnumSet.of(GroupState.PAUSED));

        // MIG_POLICY_UPDATE (GCP): No row (handled above), UNMANAGED, REVERTED, LEGACY_UNCONFIRMED.
        m.put(OperationKind.MIG_POLICY_UPDATE, EnumSet.of(
                GroupState.UNMANAGED, GroupState.REVERTED, GroupState.LEGACY_UNCONFIRMED));

        // REVERT: ACTIVE, DEGRADED, FALLBACK, PAUSED (any), APPLYING, LEGACY_UNCONFIRMED, PLANNED, FAILED.
        m.put(OperationKind.REVERT, EnumSet.of(
                GroupState.ACTIVE, GroupState.DEGRADED, GroupState.FALLBACK, GroupState.PAUSED,
                GroupState.APPLYING, GroupState.LEGACY_UNCONFIRMED, GroupState.PLANNED, GroupState.FAILED));

        return m;
    }
}
