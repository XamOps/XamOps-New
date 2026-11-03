package com.xammer.cloud.service.autospotting.core.lifecycle;

import com.xammer.cloud.domain.autospotting.GroupState;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The group lifecycle transition table (design-core.md §2.1-§2.3, §2.2.1). Encodes every allowed
 * edge of the §2.2 diagram and refuses anything else, so a transition the state machine does not
 * permit can never be written (FR-SPN-02, {@code GroupLifecycleService} calls {@link #isAllowed}
 * before the guarded native UPDATE).
 *
 * <p>The edges are taken verbatim from the §2.2 {@code stateDiagram-v2}, including DC-05's
 * FALLBACK → DEGRADED and DEGRADED → FALLBACK, and the self-edges PLANNED → PLANNED (a superseding
 * enable plan, §7.4) and PAUSED → PAUSED (a SAFETY fallback mutation verified under switch (d) or an
 * operator pause, §6.4). An idempotent self-transition for any state (target equals current state,
 * same operation) is handled by {@code GroupLifecycleService} as a no-op success (§2.3) and is not
 * modelled as an allowed edge here unless the diagram draws it.
 *
 * <p>GONE is reachable from every non-terminal persisted state (the diagram draws it from UNMANAGED,
 * PLANNED, REVERTED, LEGACY_UNCONFIRMED, APPLYING, ACTIVE, DEGRADED, FALLBACK, PAUSED, REVERTING and
 * FAILED). The two entry pseudo-states ({@code [*] → UNMANAGED}, {@code [*] → LEGACY_UNCONFIRMED})
 * are inserts, not transitions, so they are not edges here.
 */
public final class GroupTransitions {

    private static final Map<GroupState, Set<GroupState>> ALLOWED = buildAllowed();

    private GroupTransitions() {
    }

    /** True when {@code from → to} is an edge of the §2.2 diagram (or 2.2.1 compensation). */
    public static boolean isAllowed(GroupState from, GroupState to) {
        if (from == null || to == null) {
            return false;
        }
        Set<GroupState> targets = ALLOWED.get(from);
        return targets != null && targets.contains(to);
    }

    /** The allowed target states from {@code from} (empty for a state with no outgoing edge). */
    public static Set<GroupState> allowedFrom(GroupState from) {
        Set<GroupState> targets = ALLOWED.get(from);
        return targets == null ? EnumSet.noneOf(GroupState.class) : EnumSet.copyOf(targets);
    }

    private static Map<GroupState, Set<GroupState>> buildAllowed() {
        Map<GroupState, Set<GroupState>> m = new EnumMap<>(GroupState.class);

        // UNMANAGED --> PLANNED (enable plan created); --> GONE (not found, or new stable id).
        put(m, GroupState.UNMANAGED, GroupState.PLANNED, GroupState.GONE);

        // REVERTED --> PLANNED (new enable plan); --> GONE.
        put(m, GroupState.REVERTED, GroupState.PLANNED, GroupState.GONE);

        // PLANNED --> PLANNED (superseding enable plan, inherits from_state, 7.4);
        //         --> UNMANAGED / REVERTED (plan expired/superseded/cancelled before start, to from_state);
        //         --> APPLYING (enable op starts); --> GONE.
        put(m, GroupState.PLANNED,
                GroupState.PLANNED, GroupState.UNMANAGED, GroupState.REVERTED, GroupState.APPLYING,
                GroupState.GONE);

        // LEGACY_UNCONFIRMED --> APPLYING (adopt op); --> REVERTING (revert op);
        //                    --> UNMANAGED (marker and Spot share gone); --> GONE (group not found).
        put(m, GroupState.LEGACY_UNCONFIRMED,
                GroupState.APPLYING, GroupState.REVERTING, GroupState.UNMANAGED, GroupState.GONE);

        // APPLYING --> ACTIVE (rollout verified, or compensated to prior config);
        //          --> REVERTING (enable compensation, or revert preempts, 4.7);
        //          --> UNMANAGED / REVERTED (no mutation yet, prior_state);
        //          --> LEGACY_UNCONFIRMED (adopt failed or compensated);
        //          --> PAUSED (drift re-apply failed or compensated);
        //          --> FALLBACK (exit failed/compensated, FALLBACK re-apply verified, preempting fallback enter verified, 4.7);
        //          --> FAILED (compensation to prior config failed); --> GONE (group not found).
        put(m, GroupState.APPLYING,
                GroupState.ACTIVE, GroupState.REVERTING, GroupState.UNMANAGED, GroupState.REVERTED,
                GroupState.LEGACY_UNCONFIRMED, GroupState.PAUSED, GroupState.FALLBACK, GroupState.FAILED,
                GroupState.GONE);

        // ACTIVE --> APPLYING (config/profile op); --> FALLBACK (fallback mutation verified);
        //        --> DEGRADED (unhealthy, no fallback path); --> PAUSED (switch d/e, operator, drift);
        //        --> REVERTING (revert op); --> GONE.
        put(m, GroupState.ACTIVE,
                GroupState.APPLYING, GroupState.FALLBACK, GroupState.DEGRADED, GroupState.PAUSED,
                GroupState.REVERTING, GroupState.GONE);

        // DEGRADED --> ACTIVE (healthy again, MANAGED);
        //          --> FALLBACK (fallback mutation verified MANAGED, or healthy again FALLBACK, DC-05);
        //          --> PAUSED (switch d/e, operator, drift); --> REVERTING (revert op); --> GONE.
        put(m, GroupState.DEGRADED,
                GroupState.ACTIVE, GroupState.FALLBACK, GroupState.PAUSED, GroupState.REVERTING,
                GroupState.GONE);

        // FALLBACK --> APPLYING (fallback exit op); --> DEGRADED (On-Demand short 20 min after entry, DC-05);
        //          --> PAUSED (switch d/e, operator, drift); --> REVERTING (revert op); --> GONE.
        put(m, GroupState.FALLBACK,
                GroupState.APPLYING, GroupState.DEGRADED, GroupState.PAUSED, GroupState.REVERTING,
                GroupState.GONE);

        // PAUSED --> APPLYING (drift re-apply op starts);
        //        --> PAUSED (SAFETY fallback mutation verified under d or operator, paused_from=FALLBACK);
        //        --> ACTIVE / FALLBACK / DEGRADED (released or baseline accepted, to paused_from);
        //        --> REVERTING (revert op); --> GONE.
        put(m, GroupState.PAUSED,
                GroupState.APPLYING, GroupState.PAUSED, GroupState.ACTIVE, GroupState.FALLBACK,
                GroupState.DEGRADED, GroupState.REVERTING, GroupState.GONE);

        // REVERTING --> REVERTED (snapshot restored, verified); --> FAILED (revert step failed terminally);
        //           --> ACTIVE / DEGRADED / FALLBACK / PAUSED / LEGACY_UNCONFIRMED (revert ended before any mutation, prior_state, DR-04);
        //           --> GONE.
        put(m, GroupState.REVERTING,
                GroupState.REVERTED, GroupState.FAILED, GroupState.ACTIVE, GroupState.DEGRADED,
                GroupState.FALLBACK, GroupState.PAUSED, GroupState.LEGACY_UNCONFIRMED, GroupState.GONE);

        // FAILED --> REVERTING (revert op, acknowledged REVERT_FROM_FAILED, 7.4); --> GONE.
        put(m, GroupState.FAILED, GroupState.REVERTING, GroupState.GONE);

        // GONE and REVERTED are terminal for automation (REVERTED's only edges are above: PLANNED, GONE).
        put(m, GroupState.GONE); // no outgoing edge

        return m;
    }

    private static void put(Map<GroupState, Set<GroupState>> m, GroupState from, GroupState... targets) {
        EnumSet<GroupState> set = EnumSet.noneOf(GroupState.class);
        for (GroupState t : targets) {
            set.add(t);
        }
        m.put(from, set);
    }
}
