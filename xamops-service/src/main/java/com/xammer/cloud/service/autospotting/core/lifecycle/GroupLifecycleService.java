package com.xammer.cloud.service.autospotting.core.lifecycle;

import com.xammer.cloud.domain.autospotting.ConfigMode;
import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.PauseReason;
import com.xammer.cloud.domain.autospotting.SpotGroup;
import com.xammer.cloud.repository.autospotting.SpotGroupLifecycleRepository;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter.Actor;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter.AuditRecord;
import com.xammer.cloud.service.autospotting.core.lease.FencedTx;
import com.xammer.cloud.service.autospotting.core.lease.LeaseHandle;
import com.xammer.cloud.service.autospotting.notify.AutoSpottingNotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * The one place that changes a group's {@code state} (design-core.md §2.1-§2.3, §2.4 "Owned";
 * plan-phase-06.md task 6.1). Providers return outcomes; they never set state (§9). Every lifecycle
 * write is one guarded native UPDATE on {@code state} and {@code version}
 * ({@code version = version + 1}), the matching append-only {@code spot_group_transition} insert
 * with a clamped {@code effective_at}, the audit record and the customer notification outbox row (if the
 * transition has one, ux §7.1) — all in one transaction.
 *
 * <p>Two entry points:
 * <ul>
 *   <li>{@link #transition} runs inside a {@link FencedTx} on the group lease: the fence is the
 *       transaction's first statement, so a lost lease rolls the whole unit back with nothing
 *       committed (AC-SPN-03). The runner and the reconciler use it while holding the group lease.</li>
 *   <li>{@link #transitionUnderRowLock} serves the plan-driven exceptions of §2.3 (PLANNED on plan
 *       creation and on plan expiry): a group with no op has no lease holder, so the row is locked
 *       with {@code SELECT ... FOR UPDATE} under the same {@code (state, version)} guard.</li>
 * </ul>
 *
 * <p>Column ownership (§2.4): only lifecycle columns are written, and
 * {@code desired_config}/{@code desired_fingerprint} only when the caller passes a config write,
 * which the three write points of §2.4 use. Runtime columns written by another actor (the reconciler)
 * never fail a transition, because the guard is on {@code state} and {@code version} only, and
 * {@code version} is not a JPA {@code @Version}.
 *
 * <p>Idempotency (§2.3): a transition whose target equals the current state, made by the same
 * operation ({@code current_operation_id} matches), is a no-op success, so a reconciler or a retried
 * step can re-issue a transition after a crash between commit and acknowledgment (AC-SPN-06).
 */
@Service
public class GroupLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(GroupLifecycleService.class);

    private final FencedTx fencedTx;
    private final SpotGroupLifecycleRepository lifecycle;
    private final AutoSpottingAuditWriter auditWriter;
    private final AutoSpottingNotificationService notifications;

    public GroupLifecycleService(FencedTx fencedTx, SpotGroupLifecycleRepository lifecycle,
                                 AutoSpottingAuditWriter auditWriter,
                                 AutoSpottingNotificationService notifications) {
        this.fencedTx = fencedTx;
        this.lifecycle = lifecycle;
        this.auditWriter = auditWriter;
        this.notifications = notifications;
    }

    /**
     * A requested lifecycle transition. {@code to} is the target state; {@code reason} the
     * transition's {@code reason_code}; {@code actor} who caused it; {@code op} the operation id (may
     * be null for a plan-driven write); {@code effectiveAt} when the change takes effect (clamped on
     * write, DR-01). Optional {@code pauseReason}/{@code pausedFromState}/{@code configMode} and the
     * {@code desiredConfig}/{@code desiredFingerprint} config write (only at the §2.4 write points).
     */
    public record TransitionRequest(
            GroupState to,
            String reason,
            Actor actor,
            UUID op,
            Instant effectiveAt,
            PauseReason pauseReason,
            GroupState pausedFromState,
            ConfigMode configMode,
            boolean writesDesiredConfig,
            String desiredConfig,
            String desiredFingerprint) {

        /** A plain lifecycle transition that does not write {@code desired_config}. */
        public static TransitionRequest of(GroupState to, String reason, Actor actor, UUID op, Instant effectiveAt) {
            return new TransitionRequest(to, reason, actor, op, effectiveAt, null, null, null, false, null, null);
        }

        public TransitionRequest withPause(PauseReason pauseReason, GroupState pausedFromState) {
            return new TransitionRequest(to, reason, actor, op, effectiveAt, pauseReason, pausedFromState,
                    configMode, writesDesiredConfig, desiredConfig, desiredFingerprint);
        }

        public TransitionRequest withConfigMode(ConfigMode configMode) {
            return new TransitionRequest(to, reason, actor, op, effectiveAt, pauseReason, pausedFromState,
                    configMode, writesDesiredConfig, desiredConfig, desiredFingerprint);
        }

        /** Write {@code desired_config}/{@code desired_fingerprint} in the same transaction (§2.4). */
        public TransitionRequest writingDesiredConfig(String desiredConfig, String desiredFingerprint) {
            return new TransitionRequest(to, reason, actor, op, effectiveAt, pauseReason, pausedFromState,
                    configMode, true, desiredConfig, desiredFingerprint);
        }
    }

    /** The outcome of a transition attempt. */
    public enum Result {
        /** The guarded UPDATE changed the row; the transition and audit rows were written. */
        APPLIED,
        /** Target equalled the current state for the same operation: a no-op success (§2.3). */
        NO_OP,
        /** The {@code (state, version)} guard missed (stale version or the state moved). */
        STALE
    }

    /**
     * Transition a group while holding its lease. Runs inside a {@link FencedTx} on {@code handle}:
     * the fence is first, so a lost lease throws {@code LeaseLostException} and rolls everything back.
     *
     * @throws IllegalArgumentException if {@code from → to} is not an allowed edge (§2.2)
     */
    public Result transition(LeaseHandle handle, SpotGroup group, TransitionRequest request) {
        return fencedTx.call(handle, () -> apply(group, request));
    }

    /**
     * A transition that also writes {@code fallback_fingerprint} ({@code null} clears it) in the same fenced
     * transaction (design-aws.md §6.4 step 3: FALLBACK, {@code config_mode=FALLBACK} and the fingerprint land
     * together or not at all). A stale guard writes nothing.
     */
    public Result transitionWithFallbackFingerprint(LeaseHandle handle, SpotGroup group, TransitionRequest request,
                                                    String fallbackFingerprint) {
        return fencedTx.call(handle, () -> {
            Result result = apply(group, request);
            if (result != Result.STALE) {
                lifecycle.setFallbackFingerprint(group.getId(), fallbackFingerprint);
            }
            return result;
        });
    }

    /**
     * The plan-driven exceptions of §2.3 (PLANNED on plan creation and on plan expiry): a group with
     * no op has no lease holder, so lock the row with {@code SELECT ... FOR UPDATE} under the same
     * guard, then apply. Runs in a new transaction so the row lock is scoped to this unit of work.
     *
     * @throws IllegalArgumentException if {@code from → to} is not an allowed edge (§2.2)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Result transitionUnderRowLock(SpotGroup group, TransitionRequest request) {
        Long lockedVersion = lifecycle.lockRow(group.getId(), group.getState());
        if (lockedVersion == null) {
            // The row's state moved before we locked it; treat as a missed guard (caller re-reads).
            return Result.STALE;
        }
        return applyGuarded(group, group.getState(), lockedVersion, request);
    }

    // The shared body, run inside whichever transaction the entry point opened (fenced or row-locked).
    private Result apply(SpotGroup group, TransitionRequest request) {
        return applyGuarded(group, group.getState(), group.getVersion(), request);
    }

    private Result applyGuarded(SpotGroup group, GroupState from, long expectedVersion,
                                TransitionRequest request) {
        GroupState to = request.to();

        // Idempotent self-transition (§2.3): same target, same operation → no-op success.
        if (from == to && sameOperation(group, request.op())) {
            if (log.isDebugEnabled()) {
                log.debug("lifecycle no-op {} stays {} (op={})", group.getId(), to, request.op());
            }
            return Result.NO_OP;
        }

        if (!GroupTransitions.isAllowed(from, to)) {
            throw new IllegalArgumentException(
                    "forbidden group transition " + from + " -> " + to + " for group " + group.getId());
        }

        Instant effectiveAt = clamp(group.getId(), request.effectiveAt());

        int updated = request.writesDesiredConfig()
                ? lifecycle.transitionWithConfig(group.getId(), from, expectedVersion, to,
                        request.pauseReason(), request.pausedFromState(), request.configMode(), request.op(),
                        effectiveAt, request.desiredConfig(), request.desiredFingerprint())
                : lifecycle.transition(group.getId(), from, expectedVersion, to,
                        request.pauseReason(), request.pausedFromState(), request.configMode(), request.op(),
                        effectiveAt);

        if (updated == 0) {
            // Zero rows: a stale version or the state moved. The caller re-reads; no blind retry (§2.3).
            return Result.STALE;
        }

        Actor actor = request.actor();
        lifecycle.insertTransition(group.getId(), from, to, request.reason(),
                actor.type().name(), actorId(actor), request.op(), effectiveAt, effectiveAt);

        auditWriter.write(AuditRecord.builder("GROUP_TRANSITION", actor, request.reason(), "SUCCESS")
                .operationId(request.op())
                .provider(group.getProvider() == null ? null : group.getProvider().name())
                .clientId(group.getClientId())
                .cloudAccountId(group.getCloudAccountId())
                .groupId(group.getId())
                .groupKey(group.getGroupKey())
                .beforeState(stateJson(from))
                .afterState(stateJson(to))
                .build());

        // The customer notice for this transition (if it has one) is an outbox row in the same transaction,
        // so a rolled-back transition leaves no notice and a retried one can't duplicate it (ux §7.1).
        notifications.onTransition(group, from, to, request.reason(), request.op(), request.pauseReason(),
                request.writesDesiredConfig());

        if (log.isDebugEnabled()) {
            log.debug("lifecycle {} {} -> {} reason={} op={}", group.getId(), from, to, request.reason(),
                    request.op());
        }
        return Result.APPLIED;
    }

    private Instant clamp(UUID groupId, Instant requested) {
        Instant latest = lifecycle.latestEffectiveAt(groupId);
        if (latest == null) {
            return requested;
        }
        // DR-01: effective_at = GREATEST(requested, latest effective_at of the group's transitions).
        return requested.isBefore(latest) ? latest : requested;
    }

    private static boolean sameOperation(SpotGroup group, UUID op) {
        if (op == null) {
            return group.getCurrentOperationId() == null;
        }
        return op.equals(group.getCurrentOperationId());
    }

    private static String actorId(Actor actor) {
        if (actor.userId() != null) {
            return String.valueOf(actor.userId());
        }
        return null;
    }

    private static String stateJson(GroupState state) {
        return state == null ? null : "{\"state\":\"" + state.name() + "\"}";
    }
}
