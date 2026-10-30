package com.xammer.cloud.service.autospotting.core.reconcile;

import com.xammer.cloud.domain.autospotting.AutoSpottingSwitch;
import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.PauseReason;
import com.xammer.cloud.domain.autospotting.SpotGroup;
import com.xammer.cloud.repository.autospotting.AutoSpottingSwitchRepository;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter.Actor;
import com.xammer.cloud.service.autospotting.core.lease.LeaseHandle;
import com.xammer.cloud.service.autospotting.core.lifecycle.GroupLifecycleService;
import com.xammer.cloud.service.autospotting.core.lifecycle.GroupLifecycleService.TransitionRequest;
import com.xammer.cloud.service.autospotting.core.switches.SwitchState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Step 4 of the sweep (design-core.md §5.1, §6.4; plan-phase-08.md task 8.1, GR-06): apply any
 * pending switch-driven PAUSE first, and any switch release, before the health transitions. Engaging
 * the per-account switch (d) {@code ACCOUNT:{id}} or the emergency freeze (e) {@code FREEZE} moves a
 * stable group (ACTIVE, DEGRADED, FALLBACK) to PAUSED with {@code pause_reason} ACCOUNT_SWITCH or
 * FREEZE and {@code paused_from_state} the current state; releasing the switch restores
 * {@code paused_from_state} (unless drift was found meanwhile, which the reconciler handles).
 *
 * <p><b>GR-06 (switch-cache race).</b> The sweep's cached view may be stale. This applier re-reads
 * the (d) and (e) rows <em>inside</em> the fenced transaction of each health-transition write
 * ({@link #reReadEngaged}), uncached and straight from {@code autospotting_switch}. If either is
 * engaged it writes the pause (with the clamped {@code effective_at}) instead of the health
 * transition, so a DEGRADED signal that arrives together with a switch engage is never written as
 * ACTIVE → DEGRADED after the pause.
 *
 * <p>The pause transition's {@code effective_at} is
 * {@code GREATEST(switch.changed_at, latest transition effective_at)} (§6.4, ledger §6.2): billing
 * stops when the switch was engaged, not up to 5 min later at the sweep.
 *
 * <p>The transition writes go through {@link GroupLifecycleService} under the group lease the
 * reconciler holds, so they are fenced (AC-SPN-03).
 */
@Component
public class SwitchPauseApplier {

    private static final Logger log = LoggerFactory.getLogger(SwitchPauseApplier.class);

    private final AutoSpottingSwitchRepository switchRepository;
    private final GroupLifecycleService lifecycle;

    public SwitchPauseApplier(AutoSpottingSwitchRepository switchRepository, GroupLifecycleService lifecycle) {
        this.switchRepository = switchRepository;
        this.lifecycle = lifecycle;
    }

    /** Whether switch (d) or (e) is engaged for this group in {@code state} (the uncached sweep read). */
    public boolean pauseEngaged(SwitchState state, SpotGroup group) {
        return state.freezeEngaged() || state.accountEngaged(group.getCloudAccountId());
    }

    /** The pause reason for an engaged switch: FREEZE (e) wins over ACCOUNT_SWITCH (d) (§6.4). */
    public PauseReason pauseReasonFor(SwitchState state, SpotGroup group) {
        if (state.freezeEngaged()) {
            return PauseReason.FREEZE;
        }
        if (state.accountEngaged(group.getCloudAccountId())) {
            return PauseReason.ACCOUNT_SWITCH;
        }
        return null;
    }

    /**
     * Apply a switch-driven PAUSE to a stable group (§6.4). Writes the PAUSE transition fenced on
     * {@code handle}, storing {@code paused_from_state=current} and the clamped {@code effective_at}.
     * No-op (returns false) when the group is not in a stable state.
     */
    public boolean applyPause(LeaseHandle handle, SpotGroup group, PauseReason reason) {
        GroupState from = group.getState();
        if (!isStable(from)) {
            return false;
        }
        Instant effectiveAt = effectiveAt(reason, group);
        TransitionRequest req = TransitionRequest
                .of(GroupState.PAUSED, reasonCode(reason), Actor.system(), group.getCurrentOperationId(), effectiveAt)
                .withPause(reason, from);
        GroupLifecycleService.Result result = lifecycle.transition(handle, group, req);
        if (result == GroupLifecycleService.Result.APPLIED && log.isDebugEnabled()) {
            log.debug("group {} paused {} ({}) effectiveAt={}", group.getId(), reason, from, effectiveAt);
        }
        return result == GroupLifecycleService.Result.APPLIED;
    }

    /**
     * Release a switch pause: restore {@code paused_from_state} when neither (d) nor (e) is engaged
     * (§6.4). Only for a group currently PAUSED(ACCOUNT_SWITCH) or PAUSED(FREEZE). Returns true when
     * the release transition was written.
     */
    public boolean applyRelease(LeaseHandle handle, SpotGroup group, SwitchState freshState) {
        if (group.getState() != GroupState.PAUSED) {
            return false;
        }
        PauseReason reason = group.getPauseReason();
        boolean switchPause = reason == PauseReason.ACCOUNT_SWITCH || reason == PauseReason.FREEZE;
        if (!switchPause) {
            return false; // PAUSED(OPERATOR) and PAUSED(DRIFT) are released by their own paths
        }
        if (pauseEngaged(freshState, group)) {
            return false; // still engaged: stay paused
        }
        GroupState target = group.getPausedFromState();
        if (target == null) {
            return false;
        }
        TransitionRequest req = TransitionRequest.of(target, "SWITCH_RELEASED", Actor.system(),
                group.getCurrentOperationId(), Instant.now());
        GroupLifecycleService.Result result = lifecycle.transition(handle, group, req);
        if (result == GroupLifecycleService.Result.APPLIED && log.isDebugEnabled()) {
            log.debug("group {} released from PAUSED({}) to {}", group.getId(), reason, target);
        }
        return result == GroupLifecycleService.Result.APPLIED;
    }

    /**
     * GR-06 re-read: read the (d) and (e) rows for this group uncached inside the caller's fenced
     * transaction. Returns the engaged pause reason (FREEZE wins), or null when neither is engaged
     * right now. The reconciler calls this before every health-transition write and, if non-null,
     * writes the pause instead of the health transition.
     */
    public PauseReason reReadEngaged(SpotGroup group) {
        List<String> keys = List.of(SwitchState.FREEZE, SwitchState.accountKey(group.getCloudAccountId()));
        List<AutoSpottingSwitch> engaged;
        try {
            engaged = switchRepository.findEngagedByScopeKeys(keys);
        } catch (RuntimeException e) {
            // Fail closed (§6.1): an unreadable switch store counts as every switch engaged, so pause.
            log.warn("switch re-read failed; failing closed to FREEZE for group {}: {}", group.getId(),
                    e.toString());
            return PauseReason.FREEZE;
        }
        boolean freeze = engaged.stream().anyMatch(s -> SwitchState.FREEZE.equals(s.getScopeKey()));
        if (freeze) {
            return PauseReason.FREEZE;
        }
        boolean account = engaged.stream()
                .anyMatch(s -> SwitchState.accountKey(group.getCloudAccountId()).equals(s.getScopeKey()));
        return account ? PauseReason.ACCOUNT_SWITCH : null;
    }

    /** The clamped {@code effective_at} for a switch pause: GREATEST(switch.changed_at, latest). */
    private Instant effectiveAt(PauseReason reason, SpotGroup group) {
        Instant changedAt = switchChangedAt(reason, group);
        Instant now = Instant.now();
        // The GroupLifecycleService.clamp applies GREATEST(requested, latest transition). We pass the
        // switch's changed_at as the requested effective_at (§6.4); clamp never moves it earlier than
        // the latest transition, so the pause is never ordered before an already-written transition.
        return changedAt != null ? changedAt : now;
    }

    private Instant switchChangedAt(PauseReason reason, SpotGroup group) {
        String scopeKey = reason == PauseReason.FREEZE
                ? SwitchState.FREEZE
                : SwitchState.accountKey(group.getCloudAccountId());
        Map<String, Instant> changedAt;
        try {
            changedAt = switchRepository.findEngagedByScopeKeys(List.of(scopeKey)).stream()
                    .collect(Collectors.toMap(AutoSpottingSwitch::getScopeKey, AutoSpottingSwitch::getChangedAt));
        } catch (RuntimeException e) {
            return null; // fall back to now (caller clamps); the store read already failed closed above
        }
        return changedAt.get(scopeKey);
    }

    private static String reasonCode(PauseReason reason) {
        return reason == PauseReason.FREEZE ? "FREEZE_ENGAGED" : "ACCOUNT_SWITCH_ENGAGED";
    }

    private static boolean isStable(GroupState state) {
        return state == GroupState.ACTIVE || state == GroupState.DEGRADED || state == GroupState.FALLBACK;
    }
}
