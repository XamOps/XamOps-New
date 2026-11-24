package com.xammer.cloud.service.autospotting.ledger.calc;

import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs.RevertOp;
import com.xammer.cloud.service.autospotting.ledger.calc.CalcInputs.Transition;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The state windows of one group: which parts of the accrual day belong to {@code M(W)}, the measured
 * windows (design-ledger.md §3.1, §6.2). Pure and deterministic. Times are epoch seconds.
 *
 * <ul>
 *   <li>The state after row k is row k's {@code to_state}, rows read in {@code id} order, and a row
 *       takes effect at its {@code effective_at}. Rows with the same {@code effective_at} resolve to the
 *       later {@code id}, so a zero-length interval contributes nothing (DR-01).</li>
 *   <li>If two rows of the group disagree between {@code id} order and {@code effective_at} order inside
 *       the day, the day is not guessed: {@link #orderViolation()} is true and the caller makes it
 *       {@code UNKNOWN(MISSING_STATE_ORDER)}.</li>
 *   <li>Only ACTIVE time is measured (PO-04), at or after go-live, and outside the revert window.</li>
 *   <li>Revert window (GR-07): a REVERT excludes {@code [confirmed_at, op finished_at)}. ACTIVE time after a
 *       REVERTING to prior-state transition, or after a revert op that ended with
 *       {@code first_mutation_seq} null, is measured again. An op that is still open excludes until
 *       the end of the day.</li>
 * </ul>
 */
final class StateWindows {
    /** Where a second sits relative to {@code M(W)}. */
    enum Kind { MEASURED, EXCLUDED, NONE }

    /** Classification of one instant: measured, excluded with a reason, or outside any managed state. */
    record Cls(Kind kind, String reason, GroupState state) {
        boolean measured() {
            return kind == Kind.MEASURED;
        }
    }

    private static final Cls MEASURED = new Cls(Kind.MEASURED, null, GroupState.ACTIVE);
    private static final Set<GroupState> NOT_MANAGED = EnumSet.of(GroupState.UNMANAGED, GroupState.PLANNED,
            GroupState.GONE);
    /** States a REVERTING group returns to when the revert is undone (core §2.2 REVERTING to prior_state). */
    private static final Set<GroupState> PRIOR_STATES = EnumSet.of(GroupState.ACTIVE, GroupState.DEGRADED,
            GroupState.FALLBACK, GroupState.PAUSED, GroupState.LEGACY_UNCONFIRMED);

    private final List<Transition> byId;
    private final Long goLiveAt;
    private final List<long[]> revertWindows = new ArrayList<>();
    private final boolean orderViolation;
    private final TreeSet<Long> breakpoints = new TreeSet<>();

    StateWindows(List<Transition> transitions, List<RevertOp> revertOps, Long goLiveAt, long windowStart,
                 long windowEnd) {
        this.byId = transitions.stream().sorted(Comparator.comparingLong(Transition::id)).toList();
        this.goLiveAt = goLiveAt;
        this.orderViolation = detectViolation(windowStart, windowEnd);
        for (Transition t : byId) {
            breakpoints.add(Instants.sec(t.effectiveAt()));
        }
        if (goLiveAt != null) {
            breakpoints.add(goLiveAt);
        }
        for (RevertOp op : revertOps) {
            if (op.confirmedAt() == null) {
                continue;
            }
            long start = Instants.sec(op.confirmedAt());
            long end = op.finishedAt() == null ? Long.MAX_VALUE : Instants.sec(op.finishedAt());
            for (Transition t : byId) {
                long at = Instants.sec(t.effectiveAt());
                if (t.fromState() == GroupState.REVERTING && PRIOR_STATES.contains(t.toState()) && at >= start) {
                    end = Math.min(end, at);
                    break;
                }
            }
            revertWindows.add(new long[] {start, end});
            breakpoints.add(start);
            if (end != Long.MAX_VALUE) {
                breakpoints.add(end);
            }
        }
    }

    boolean orderViolation() {
        return orderViolation;
    }

    /** Every instant at which a classification can change. */
    Set<Long> breakpoints() {
        return breakpoints;
    }

    GroupState stateAt(long t) {
        GroupState state = null;
        for (Transition tr : byId) {
            if (Instants.sec(tr.effectiveAt()) <= t) {
                state = tr.toState();
            }
        }
        return state;
    }

    Cls classify(long t) {
        GroupState state = stateAt(t);
        if (state == null || NOT_MANAGED.contains(state)) {
            return new Cls(Kind.NONE, null, state);
        }
        if (goLiveAt == null || t < goLiveAt) {
            return new Cls(Kind.EXCLUDED, Reasons.BEFORE_GO_LIVE, state);
        }
        if (state == GroupState.LEGACY_UNCONFIRMED) {
            return new Cls(Kind.EXCLUDED, Reasons.LEGACY_UNCONFIRMED, state);
        }
        if (state != GroupState.ACTIVE) {
            return new Cls(Kind.EXCLUDED, Reasons.STATE_PREFIX + state.name(), state);
        }
        for (long[] w : revertWindows) {
            if (t >= w[0] && t < w[1]) {
                return new Cls(Kind.EXCLUDED, Reasons.STATE_REVERTING, state);
            }
        }
        return MEASURED;
    }

    /** True if the group spent some of the second {@code t} in a state that earns an entry (design-ledger.md §6.1). */
    boolean entryState(long t) {
        GroupState state = stateAt(t);
        return state != null && !NOT_MANAGED.contains(state) && state != GroupState.REVERTED;
    }

    private boolean detectViolation(long windowStart, long windowEnd) {
        for (int i = 1; i < byId.size(); i++) {
            long previous = Instants.sec(byId.get(i - 1).effectiveAt());
            long current = Instants.sec(byId.get(i).effectiveAt());
            if (current < previous) {
                // The pair disagrees: the days it spans are affected.
                if (current < windowEnd && previous >= windowStart) {
                    return true;
                }
            }
        }
        return false;
    }
}
