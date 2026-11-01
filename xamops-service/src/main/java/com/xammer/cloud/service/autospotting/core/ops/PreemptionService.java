package com.xammer.cloud.service.autospotting.core.ops;

import com.xammer.cloud.domain.autospotting.ActionClass;
import com.xammer.cloud.domain.autospotting.OperationKind;
import com.xammer.cloud.domain.autospotting.SpotOperation;
import com.xammer.cloud.repository.autospotting.SpotGroupLifecycleRepository;
import com.xammer.cloud.repository.autospotting.SpotOperationRepository;
import com.xammer.cloud.service.autospotting.core.lifecycle.CompensationTargets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Busy-group, preemption and reconciler-requested compensation (design-core.md §4.7; plan-phase-07.md
 * task 7.3). Called by confirm (Phase 11) and by the reconciler (Phase 08) under
 * {@code SELECT ... FROM spot_group WHERE id = :id FOR UPDATE} (no lease needed to preempt, §4.7): it
 * sets {@code cancel_requested}/{@code compensate_requested} on an existing op with a conditional
 * UPDATE and — for the caller's benefit — reports whether the new op may be inserted QUEUED.
 *
 * <p>The §4.7 decision table:
 * <ul>
 *   <li><b>PROGRESS</b> (ENABLE, ADOPT, CONFIG_APPLY, PROFILE_CHANGE, ACCEPT_BASELINE,
 *       MIG_POLICY_UPDATE) vs any non-terminal op → {@code AS_GROUP_BUSY}.</li>
 *   <li><b>REVERT</b> preempts a PROGRESS op or a FALLBACK_ENTER in any status
 *       ({@code PREEMPTED_BY_REVERT}); REVERT vs REVERT → busy.</li>
 *   <li><b>FALLBACK_ENTER</b> preempts CONFIG_APPLY / PROFILE_CHANGE / FALLBACK_EXIT only after their
 *       first mutation ({@code PREEMPTED_BY_FALLBACK}); never ENABLE / ADOPT / REVERT /
 *       FALLBACK_ENTER.</li>
 * </ul>
 *
 * <p><b>The {@code desired_config} deferral.</b> When FALLBACK_ENTER preempts a CONFIG_APPLY /
 * PROFILE_CHANGE after its mutation, the runner (not this service) writes the preempted op's
 * {@code target_config} to {@code desired_config} in the fenced transaction that ends the op
 * CANCELLED (§2.4, §4.7), so the customer's confirmed change is applied at fallback exit. This
 * service records that a deferral was requested so the caller can surface it; the
 * {@code CONFIG_DEFERRED_BY_FALLBACK} customer notice's outbox lands in Phase 24 (plan-phase-07.md
 * 7.3: "outbox lands in Phase 24, the row is written now"). Until that table exists, the deferral is
 * carried on the preemption outcome and recorded in the audit trail by the caller; no new table or
 * migration is introduced (plan-phase-07.md "Migrations: None").
 *
 * <p><b>Dark.</b> No caller invokes this in production this phase (confirm is Phase 11, the
 * reconciler Phase 08). It mutates no cloud and enables no customer-visible path.
 */
@Service
public class PreemptionService {

    private static final Logger log = LoggerFactory.getLogger(PreemptionService.class);

    /** Cancel reasons (design-core.md §4.5 {@code cancel_reason}). */
    public static final String PREEMPTED_BY_REVERT = "PREEMPTED_BY_REVERT";
    public static final String PREEMPTED_BY_FALLBACK = "PREEMPTED_BY_FALLBACK";

    private final SpotOperationRepository operations;
    private final SpotGroupLifecycleRepository lifecycle;

    public PreemptionService(SpotOperationRepository operations, SpotGroupLifecycleRepository lifecycle) {
        this.operations = operations;
        this.lifecycle = lifecycle;
    }

    /** The outcome of a preemption request (what the caller should do next). */
    public enum Verdict {
        /** No non-terminal op existed; the caller may insert the new op QUEUED. */
        CLEAR,
        /** An existing op was preempted (cancel/compensate requested); insert the new op QUEUED. */
        PREEMPTED,
        /** The group is busy; the caller returns 409 {@code AS_GROUP_BUSY} (no op inserted). */
        BUSY
    }

    /**
     * The preemption decision for a new op of {@code newKind} on {@code groupId}, plus the preempted
     * op id (null for CLEAR/BUSY) and whether a CONFIG_APPLY/PROFILE_CHANGE deferral should be
     * written when that op ends CANCELLED (§4.7, §2.4).
     */
    public record Preemption(Verdict verdict, UUID preemptedOpId, boolean configDeferred) {

        static Preemption clear() {
            return new Preemption(Verdict.CLEAR, null, false);
        }

        /**
         * A CLEAR verdict for a plan on a group with no {@code spot_group} row yet (§2.1: a row
         * appears with the first plan). There is no row to lock or op to preempt, so the caller may
         * insert the new op QUEUED. Public so the confirm path (core.plan) can use it.
         */
        public static Preemption clearForRowlessPlan() {
            return new Preemption(Verdict.CLEAR, null, false);
        }

        static Preemption busy() {
            return new Preemption(Verdict.BUSY, null, false);
        }

        static Preemption preempted(UUID opId, boolean configDeferred) {
            return new Preemption(Verdict.PREEMPTED, opId, configDeferred);
        }
    }

    /**
     * Evaluate and apply the §4.7 preemption rules for {@code newKind} on {@code groupId}, under the
     * group row lock ({@code SELECT ... FOR UPDATE}). On {@link Verdict#PREEMPTED} the existing op's
     * {@code cancel_requested} is set with a conditional UPDATE; the caller inserts the new op QUEUED
     * in the same transaction. Runs in a new transaction so the row lock is scoped to this unit.
     *
     * @param newKind   the kind of op the caller wants to enqueue
     * @param newOpId   the id the new op will carry (recorded as {@code preempted_by_op})
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Preemption preemptFor(UUID groupId, OperationKind newKind, UUID newOpId) {
        if (lifecycle.lockRowById(groupId) == null) {
            return Preemption.busy(); // no group row: nothing to enqueue against
        }
        List<SpotOperation> live = operations.findNonTerminalByGroup(groupId);
        if (live.isEmpty()) {
            return Preemption.clear();
        }

        // A new PROGRESS op is refused whenever any non-terminal op exists (§4.7 first row).
        if (CompensationTargets.actionClass(newKind) == ActionClass.PROGRESS && newKind != OperationKind.REVERT) {
            return Preemption.busy();
        }

        Instant now = Instant.now();
        if (newKind == OperationKind.REVERT) {
            return preemptForRevert(live, newOpId, now);
        }
        if (newKind == OperationKind.FALLBACK_ENTER) {
            return preemptForFallbackEnter(live, newOpId, now);
        }
        // Any other SAFETY kind (FALLBACK_EXIT is PROGRESS, handled above): be conservative and busy.
        return Preemption.busy();
    }

    private Preemption preemptForRevert(List<SpotOperation> live, UUID newOpId, Instant now) {
        for (SpotOperation op : live) {
            OperationKind kind = op.getKind();
            if (kind == OperationKind.REVERT) {
                return Preemption.busy(); // REVERT vs REVERT (§4.7)
            }
            // REVERT preempts a PROGRESS op in any status, and a FALLBACK_ENTER in any phase (§4.7).
            boolean preemptable = CompensationTargets.actionClass(kind) == ActionClass.PROGRESS
                    || kind == OperationKind.FALLBACK_ENTER;
            if (preemptable) {
                int updated = operations.requestCancel(op.getId(), PREEMPTED_BY_REVERT, newOpId, now);
                if (updated == 1 && log.isDebugEnabled()) {
                    log.debug("REVERT {} preempts {} {} ({})", newOpId, op.getKind(), op.getId(),
                            PREEMPTED_BY_REVERT);
                }
                return Preemption.preempted(op.getId(), false);
            }
        }
        // Only non-preemptable ops present (shouldn't happen given the kinds above): busy.
        return Preemption.busy();
    }

    private Preemption preemptForFallbackEnter(List<SpotOperation> live, UUID newOpId, Instant now) {
        for (SpotOperation op : live) {
            OperationKind kind = op.getKind();
            // Never enqueued against ENABLE, ADOPT, REVERT, FALLBACK_ENTER (§4.7 last row).
            if (kind == OperationKind.ENABLE || kind == OperationKind.ADOPT
                    || kind == OperationKind.REVERT || kind == OperationKind.FALLBACK_ENTER) {
                return Preemption.busy();
            }
            // Preempts CONFIG_APPLY / PROFILE_CHANGE / FALLBACK_EXIT only after the first mutation.
            boolean preemptableKind = kind == OperationKind.CONFIG_APPLY
                    || kind == OperationKind.PROFILE_CHANGE
                    || kind == OperationKind.FALLBACK_EXIT;
            if (preemptableKind) {
                if (op.getFirstMutationSeq() == null) {
                    // Before the first mutation the cloud is unchanged; the op ends on its own, then
                    // the ACTIVE/FALLBACK rules apply. Not enqueued now (§4.7).
                    return Preemption.busy();
                }
                int updated = operations.requestCancel(op.getId(), PREEMPTED_BY_FALLBACK, newOpId, now);
                boolean configDeferred = kind == OperationKind.CONFIG_APPLY || kind == OperationKind.PROFILE_CHANGE;
                if (updated == 1 && log.isDebugEnabled()) {
                    log.debug("FALLBACK_ENTER {} preempts {} {} ({}, configDeferred={})", newOpId,
                            op.getKind(), op.getId(), PREEMPTED_BY_FALLBACK, configDeferred);
                }
                return Preemption.preempted(op.getId(), configDeferred);
            }
        }
        return Preemption.busy();
    }

    /**
     * Reconciler-requested compensation for a switch-paused PROGRESS op past its first mutation
     * (§4.7). Under the group row lock, sets {@code compensate_requested}/{@code compensate_reason}
     * with a conditional UPDATE. Returns true when a row was updated (an eligible op was found).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean requestCompensation(UUID groupId, UUID operationId, String reason) {
        if (lifecycle.lockRowById(groupId) == null) {
            return false;
        }
        return operations.requestCompensation(operationId, reason, Instant.now()) == 1;
    }
}
