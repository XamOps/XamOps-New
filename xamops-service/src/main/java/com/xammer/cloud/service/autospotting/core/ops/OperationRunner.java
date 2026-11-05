package com.xammer.cloud.service.autospotting.core.ops;

import com.xammer.cloud.domain.autospotting.ActionClass;
import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.OperationPhase;
import com.xammer.cloud.domain.autospotting.OperationStatus;
import com.xammer.cloud.domain.autospotting.SpotGroup;
import com.xammer.cloud.domain.autospotting.SpotOperation;
import com.xammer.cloud.domain.autospotting.WaitReason;
import com.xammer.cloud.repository.autospotting.SpotGroupRepository;
import com.xammer.cloud.repository.autospotting.SpotOperationRepository;
import com.xammer.cloud.service.autospotting.core.SpotOpsLifecycle;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter.Actor;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter.AuditRecord;
import com.xammer.cloud.service.autospotting.core.lease.FencedTx;
import com.xammer.cloud.service.autospotting.core.lease.LeaseHandle;
import com.xammer.cloud.service.autospotting.core.lease.LeaseLostException;
import com.xammer.cloud.service.autospotting.core.lifecycle.CompensationTargets;
import com.xammer.cloud.service.autospotting.core.lifecycle.GroupLifecycleService;
import com.xammer.cloud.service.autospotting.core.lifecycle.GroupLifecycleService.TransitionRequest;
import com.xammer.cloud.service.autospotting.core.switches.SwitchService;
import com.xammer.cloud.service.autospotting.core.switches.SwitchState;
import com.xammer.cloud.service.autospotting.obs.AutoSpottingMdc;
import com.xammer.cloud.service.autospotting.spi.OperationContext;
import com.xammer.cloud.service.autospotting.spi.ProviderRegistry;
import com.xammer.cloud.service.autospotting.spi.SpotProvider;
import com.xammer.cloud.service.autospotting.spi.Step;
import com.xammer.cloud.service.autospotting.spi.StepOutcome;
import com.xammer.cloud.service.autospotting.spi.StepProgram;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Runs one claimed operation to a boundary (design-core.md §4.1, §4.5, §4.7, §9.4; plan-phase-07.md
 * task 7.1). The {@link OperationDispatcher} submits a runner to {@code spotOpsExecutor} for an op it
 * has claimed {@code RUNNING} while holding the group lease; the runner then:
 *
 * <ol>
 *   <li>re-derives the {@link StepProgram} from the op's immutable inputs (so any instance resumes
 *       the same program, §9.1);</li>
 *   <li>at every step boundary re-reads the switches uncached ({@link SwitchService#readUncached})
 *       and the {@code cancel_requested}/{@code compensate_requested} flags (§4.7);</li>
 *   <li>runs the step via {@link StepExecutor}; a {@link StepOutcome.InProgress} parks the op
 *       {@code WAITING(CLOUD_PROGRESS)} and <b>releases the thread while keeping the lease</b> (the
 *       heartbeat renews it), so an hours-long rollout never pins a pool thread (XC-06);</li>
 *   <li>applies {@link ErrorPolicy} to a {@link StepOutcome.Failed};</li>
 *   <li>finishes with the lifecycle transition ({@link OperationOutcomes}).</li>
 * </ol>
 *
 * <p><b>One boundary per submission.</b> The runner advances the op across consecutive <em>immediate</em>
 * steps, but any step that parks the op WAITING (cloud progress, backoff, throttle, switch pause,
 * foreign change) ends the submission: the runner records the WAITING row, releases the lease and
 * returns, and the dispatcher resubmits the op when it is due. That is what frees the thread (§4.1)
 * and lets another instance take a handed-over WAITING op.
 *
 * <p><b>Dark.</b> No {@link SpotProvider} is registered in production ({@code FakeSpotProvider} is
 * test scope), so {@link #runClaimed} never finds a provider at runtime and does nothing. No flag,
 * no caller enables a cloud-mutating path (plan-phase-07.md "Flag or rollout gate: None").
 */
@Service
public class OperationRunner {

    private static final Logger log = LoggerFactory.getLogger(OperationRunner.class);

    /** Per-call timeout for a mutating cloud request (§4.3): 15 s/attempt, 45 s/call. */
    static final Duration MUTATING_CALL_TIMEOUT = Duration.ofSeconds(45);
    /** Per-call timeout for a describe/list/get (§4.3): 10 s/attempt, 30 s/call. */
    static final Duration READ_CALL_TIMEOUT = Duration.ofSeconds(30);
    /** The poll delay the runner records for an InProgress cloud rollout (§4.1 "pollAfter"). */
    static final Duration DEFAULT_CLOUD_POLL = Duration.ofSeconds(30);

    /**
     * The persisted {@code step_seq} base for the compensation leg (§4.5, §2.2.1). A COMPENSATING op's
     * steps are persisted at {@code COMPENSATION_SEQ_BASE + index}, so their append-only
     * {@code spot_operation_step} rows never collide with the forward leg's rows at seq 0..n. A single
     * op never has more than this many forward steps, so the two seq ranges stay disjoint.
     */
    static final int COMPENSATION_SEQ_BASE = 10_000;

    private final SpotOperationRepository operations;
    private final SpotGroupRepository groups;
    private final OperationStore store;
    private final StepExecutor stepExecutor;
    private final GroupLifecycleService lifecycle;
    private final FencedTx fencedTx;
    private final AutoSpottingAuditWriter auditWriter;
    private final SwitchService switchService;
    private final SpotOpsLifecycle opsLifecycle;
    private final ProviderRegistry providers;

    public OperationRunner(SpotOperationRepository operations, SpotGroupRepository groups, OperationStore store,
                           StepExecutor stepExecutor, GroupLifecycleService lifecycle, FencedTx fencedTx,
                           AutoSpottingAuditWriter auditWriter, SwitchService switchService,
                           SpotOpsLifecycle opsLifecycle, ProviderRegistry providers) {
        this.operations = operations;
        this.groups = groups;
        this.store = store;
        this.stepExecutor = stepExecutor;
        this.lifecycle = lifecycle;
        this.fencedTx = fencedTx;
        this.auditWriter = auditWriter;
        this.switchService = switchService;
        this.opsLifecycle = opsLifecycle;
        this.providers = providers;
    }

    /**
     * Run the op {@code operationId} to its next boundary, holding {@code handle}. Called on a
     * {@code spotOpsExecutor} thread by the dispatcher (or synchronously by {@link StartupRecovery}'s
     * resume). On return the op is either terminal, parked WAITING, or still RUNNING past one more
     * boundary; the caller releases the lease.
     */
    public void runClaimed(LeaseHandle handle, UUID operationId) {
        SpotOperation op = operations.findById(operationId).orElse(null);
        if (op == null || op.getStatus() != OperationStatus.RUNNING) {
            return;
        }
        SpotGroup group = groups.findById(op.getGroupId()).orElse(null);
        if (group == null) {
            store.finishFailed(operationId, "GROUP_MISSING", "group row not found for op");
            return;
        }
        // Context for every log line of this op, cleared when the scope closes (ux §8.1).
        try (AutoSpottingMdc.Scope ignored = AutoSpottingMdc.builder()
                .operationId(op.getId())
                .groupId(group.getId())
                .provider(group.getProvider() == null ? null
                        : group.getProvider().name().toLowerCase(java.util.Locale.ROOT))
                .cloudAccountId(group.getCloudAccountId())
                .open()) {
            runClaimedScoped(handle, operationId, op, group);
        }
    }

    private void runClaimedScoped(LeaseHandle handle, UUID operationId, SpotOperation op, SpotGroup group) {
        Optional<SpotProvider> provider = providers.find(group.getProvider());
        if (provider.isEmpty()) {
            // Dark: no provider registered. Leave the op RUNNING→QUEUED-equivalent by parking it so a
            // future provider can run it; never mutate, never fail for the missing dark provider.
            store.park(operationId, WaitReason.BACKOFF, Instant.now().plus(DEFAULT_CLOUD_POLL),
                    op.getStepSeq(), null);
            if (log.isDebugEnabled()) {
                log.debug("op {} parked: no provider for {} (dark)", operationId, group.getProvider());
            }
            return;
        }
        drive(handle, op, group, provider.get());
    }

    private void drive(LeaseHandle handle, SpotOperation op, SpotGroup group, SpotProvider provider) {
        StepProgram program = deriveProgram(op, group, provider);
        // The compensation program's steps use a persisted-seq base above the forward steps, so the
        // append-only spot_operation_step rows never collide across the FORWARD and COMPENSATING legs
        // (§4.5 keys rows by (operation_id, seq)). The program index is the persisted seq minus the base.
        int base = seqBase(op);
        int seq = op.getStepSeq();
        if (seq < base) {
            seq = base; // first entry into the compensation leg
        }

        while (seq - base < program.size()) {
            int index = seq - base;
            if (opsLifecycle.isDraining()) {
                // Graceful shutdown (§4.4): stop at this boundary, keep the op resumable.
                store.park(op.getId(), WaitReason.BACKOFF, Instant.now(), seq, null);
                return;
            }

            // Re-read switches and the cancel/compensate flags at every boundary (§4.7).
            SwitchState switches = switchService.readUncached();
            SpotOperation fresh = operations.findById(op.getId()).orElse(op);

            if (handlePreemptionFlags(handle, fresh, group, program)) {
                return; // cancel or compensation took over; the op is terminal or re-driven
            }
            if (pausedBySwitch(fresh, switches)) {
                store.park(op.getId(), WaitReason.SWITCH, null, seq, null);
                if (log.isDebugEnabled()) {
                    log.debug("op {} paused at step {} by a switch", op.getId(), index);
                }
                return;
            }

            Step step = program.at(index);
            Duration callTimeout = step.mutates() ? MUTATING_CALL_TIMEOUT : READ_CALL_TIMEOUT;
            StepOutcome outcome;
            try {
                outcome = runStep(handle, op.getId(), step, seq, callTimeout);
            } catch (LeaseLostException lost) {
                // Lost the lease mid-step: stop at once. The op stays where it is; another instance
                // acquires the lease after expiry and resumes the step by probe (§4.5, §3.4).
                log.warn("op {} step {} abandoned: lease lost ({})", op.getId(), index, lost.getMessage());
                return;
            }

            if (outcome instanceof StepOutcome.Done) {
                seq++;
                store.advanceStep(op.getId(), seq);
            } else if (outcome instanceof StepOutcome.InProgress inProgress) {
                // Long cloud wait: park WAITING and release the thread, keeping the lease (§4.1).
                Instant nextAttempt = Instant.now().plus(inProgress.pollAfter());
                store.park(op.getId(), WaitReason.CLOUD_PROGRESS, nextAttempt, seq, inProgress.progressJson());
                return;
            } else if (outcome instanceof StepOutcome.Failed failed) {
                applyErrorPolicy(handle, op, group, failed, seq);
                return;
            }
        }

        // Program ran to the end: finish per the §2.2.1 success path for this phase.
        finishSuccess(handle, op, group);
    }

    /** The persisted-seq base for the op's current leg: 0 for FORWARD, {@link #COMPENSATION_SEQ_BASE} for COMPENSATING. */
    private static int seqBase(SpotOperation op) {
        return op.getPhase() == OperationPhase.COMPENSATING ? COMPENSATION_SEQ_BASE : 0;
    }

    /** Run one step; split out so {@link StartupRecovery} can resume a specific step the same way. */
    StepOutcome runStep(LeaseHandle handle, UUID operationId, Step step, int seq, Duration callTimeout) {
        opsLifecycle.beginStep();
        try {
            return stepExecutor.run(handle, operationId, step, seq, callTimeout);
        } finally {
            opsLifecycle.endStep();
        }
    }

    // ── preemption / compensation flags (§4.7) ─────────────────────────────────

    /**
     * Act on {@code cancel_requested}/{@code compensate_requested} at a boundary (§4.7). Returns true
     * when the op was taken over (ended CANCELLED, or switched to COMPENSATING and re-driven), so the
     * caller stops the forward loop.
     */
    private boolean handlePreemptionFlags(LeaseHandle handle, SpotOperation op, SpotGroup group,
                                          StepProgram program) {
        if (op.isCancelRequested()) {
            cancel(handle, op, group);
            return true;
        }
        if (op.isCompensateRequested() && op.getPhase() == OperationPhase.FORWARD
                && op.getCompensationTarget() != com.xammer.cloud.domain.autospotting.CompensationTarget.NONE) {
            // The reconciler requested compensation for a switch-paused op (§4.7). Switch to
            // COMPENSATING and re-drive; the compensation program is re-derived from its first step.
            store.beginCompensating(op.getId(), COMPENSATION_SEQ_BASE);
            SpotOperation compensating = operations.findById(op.getId()).orElse(op);
            drive(handle, compensating, group, providers.find(group.getProvider()).orElseThrow());
            return true;
        }
        return false;
    }

    /**
     * End a preempted op CANCELLED (§4.7). With no mutation yet, the group returns to
     * {@code prior_state}; otherwise the op ends CANCELLED and the preempting REVERT/FALLBACK_ENTER
     * is the restoration, so the runner does not run this op's own compensation.
     */
    private void cancel(LeaseHandle handle, SpotOperation op, SpotGroup group) {
        String reason = op.getCancelReason();
        if (op.getFirstMutationSeq() == null) {
            // No mutation: return the group to prior_state in the same fenced transaction (§4.7).
            transitionTo(handle, group, op.getPriorState(),
                    reason == null ? "PREEMPTED_BEFORE_MUTATION" : reason, op.getId());
        }
        store.finishCancelled(op.getId(), reason);
        audit(handle, op, group, "OP_CANCELLED", "CANCELLED", reason);
        if (log.isDebugEnabled()) {
            log.debug("op {} CANCELLED reason={} (firstMutationSeq={})", op.getId(), reason,
                    op.getFirstMutationSeq());
        }
    }

    // ── switch pause (§6.3 in-flight rows) ─────────────────────────────────────

    /**
     * Whether an in-flight op must pause at this boundary for the switches (design-core.md §6.3
     * in-flight rows, plan-phase-07.md 7.1 "Switch handling"):
     * <ul>
     *   <li>a PROGRESS op pauses under (a), (b), (d), (e) and the unreadable store, and continues
     *       under (c) and (c') (a conversion mid-rollout finishes);</li>
     *   <li>a SAFETY op pauses only under (e) and the unreadable store.</li>
     * </ul>
     * A COMPENSATING op is SAFETY (§6.2), so it follows the SAFETY row whatever the forward kind.
     */
    private boolean pausedBySwitch(SpotOperation op, SwitchState switches) {
        boolean aws = group(op).getProvider() == com.xammer.cloud.domain.autospotting.Provider.AWS;
        if (switches.switchStoreUnreadable() || switches.freezeEngaged()) {
            return true; // (e) and store-unreadable pause every in-flight op, SAFETY included
        }
        boolean safety = op.getPhase() == OperationPhase.COMPENSATING
                || CompensationTargets.actionClass(op.getKind()) == ActionClass.SAFETY;
        if (safety) {
            return false; // only (e)/unreadable, handled above
        }
        // PROGRESS: pause under (a) global, (b) cloud, (d) account. (c)/(c') let a rollout finish.
        return switches.globalEngaged()
                || switches.cloudEngaged(aws)
                || switches.accountEngaged(op.getCloudAccountId());
    }

    private SpotGroup group(SpotOperation op) {
        return groups.findById(op.getGroupId()).orElseThrow();
    }

    // ── error handling (§9.4) ──────────────────────────────────────────────────

    private void applyErrorPolicy(LeaseHandle handle, SpotOperation op, SpotGroup group,
                                  StepOutcome.Failed failed, int seq) {
        int attempts = currentAttempts(op.getId(), seq);
        ErrorPolicy.Decision decision = ErrorPolicy.decide(failed, attempts);
        switch (decision.move()) {
            case RETRY_BACKOFF -> {
                store.reopenStepForRetry(op.getId(), seq, handle.generation());
                store.park(op.getId(), WaitReason.BACKOFF, Instant.now().plus(decision.backoff()), seq, null);
            }
            case WAIT_THROTTLED -> {
                store.reopenStepForRetry(op.getId(), seq, handle.generation());
                store.park(op.getId(), WaitReason.BACKOFF, Instant.now().plus(decision.backoff()), seq, null);
            }
            case WAIT_FOREIGN_CHANGE -> {
                store.reopenStepForRetry(op.getId(), seq, handle.generation());
                store.park(op.getId(), WaitReason.FOREIGN_CHANGE, Instant.now().plus(decision.backoff()), seq, null);
            }
            case COMPENSATE -> compensateOrFail(handle, op, group, failed);
            case GONE -> {
                // DR-7: the group is gone. Transition to GONE and end the op FAILED(GONE).
                transitionTo(handle, group, GroupState.GONE, "GROUP_GONE", op.getId());
                store.finishFailed(op.getId(), failed.code(), failed.redactedMessage());
                audit(handle, op, group, "OP_FAILED", "FAILED", "GONE");
            }
        }
    }

    /**
     * COMPENSATE (§9.4): before the first mutation, fail back to {@code prior_state}; after it, switch
     * to COMPENSATING toward the {@code compensation_target} and re-drive. A SAFETY op (target NONE)
     * keeps waiting — it does not compensate toward Spot — and goes FAILED only on a terminal error
     * after mutation (§2.2.1).
     */
    private void compensateOrFail(LeaseHandle handle, SpotOperation op, SpotGroup group,
                                  StepOutcome.Failed failed) {
        boolean mutatedYet = op.getFirstMutationSeq() != null;
        boolean safety = CompensationTargets.actionClass(op.getKind()) == ActionClass.SAFETY;

        if (!mutatedYet) {
            // Fail, back to prior_state (§9.4 "Before the first mutation ... fail, back to prior_state").
            transitionTo(handle, group, op.getPriorState(), "FAILED_BEFORE_MUTATION", op.getId());
            store.finishFailed(op.getId(), failed.code(), failed.redactedMessage());
            audit(handle, op, group, "OP_FAILED", "FAILED", "FAILED_BEFORE_MUTATION");
            return;
        }
        if (safety) {
            // SAFETY op after mutation: a terminal error ends it FAILED, leaving the group where it is.
            store.finishFailed(op.getId(), failed.code(), failed.redactedMessage());
            audit(handle, op, group, "OP_FAILED", "FAILED", "SAFETY_TERMINAL_ERROR");
            return;
        }
        // PROGRESS op after mutation: switch to COMPENSATING and re-drive from its first step.
        store.beginCompensating(op.getId(), COMPENSATION_SEQ_BASE);
        SpotOperation compensating = operations.findById(op.getId()).orElse(op);
        drive(handle, compensating, group, providers.find(group.getProvider()).orElseThrow());
    }

    private int currentAttempts(UUID operationId, int seq) {
        return store.stepAttempts(operationId, seq);
    }

    // ── success and lifecycle ──────────────────────────────────────────────────

    private void finishSuccess(LeaseHandle handle, SpotOperation op, SpotGroup group) {
        GroupState target = op.getPhase() == OperationPhase.COMPENSATING
                ? OperationOutcomes.compensationSuccess(op)
                : OperationOutcomes.forwardSuccess(op.getKind(), op.getPriorState());

        if (target != null) {
            transitionTo(handle, group, target, successReason(op), op.getId());
        }
        store.finishSucceeded(op.getId());
        audit(handle, op, group, "OP_SUCCEEDED", "SUCCESS",
                op.getPhase() == OperationPhase.COMPENSATING ? "COMPENSATED" : "SUCCEEDED");
        if (log.isDebugEnabled()) {
            log.debug("op {} {} SUCCEEDED -> {}", op.getId(), op.getKind(), target);
        }
    }

    private String successReason(SpotOperation op) {
        if (op.getPhase() == OperationPhase.COMPENSATING) {
            return "COMPENSATED_" + op.getCompensationTarget().name();
        }
        return "OP_" + op.getKind().name() + "_VERIFIED";
    }

    /** A guarded lifecycle transition on the group lease; a forbidden edge or a stale guard is logged. */
    private void transitionTo(LeaseHandle handle, SpotGroup group, GroupState to, String reason, UUID op) {
        SpotGroup fresh = groups.findById(group.getId()).orElse(group);
        if (fresh.getState() == to) {
            return; // already there (idempotent, §2.3)
        }
        try {
            GroupLifecycleService.Result result = lifecycle.transition(handle, fresh,
                    TransitionRequest.of(to, reason, Actor.system(), op, Instant.now()));
            if (result == GroupLifecycleService.Result.STALE && log.isDebugEnabled()) {
                log.debug("op {} transition {} -> {} missed the guard (stale); a re-drive re-reads",
                        op, fresh.getState(), to);
            }
        } catch (IllegalArgumentException forbidden) {
            // A forbidden edge for this phase's fake programs: log, do not throw (the op still finishes).
            log.warn("op {} transition {} -> {} not allowed: {}", op, fresh.getState(), to,
                    forbidden.getMessage());
        }
    }

    private void audit(LeaseHandle handle, SpotOperation op, SpotGroup group, String eventType, String outcome,
                       String reason) {
        fencedTx.run(handle, () -> auditWriter.write(
                AuditRecord.builder(eventType, Actor.system(), op.getKind().name(), outcome)
                        .operationId(op.getId())
                        .planId(op.getPlanId())
                        .provider(group.getProvider() == null ? null : group.getProvider().name())
                        .clientId(group.getClientId())
                        .cloudAccountId(group.getCloudAccountId())
                        .groupId(group.getId())
                        .groupKey(group.getGroupKey())
                        .details(reason == null ? null : "{\"reason\":\"" + reason + "\"}")
                        .build()));
    }

    private StepProgram deriveProgram(SpotOperation op, SpotGroup group, SpotProvider provider) {
        OperationContext ctx = new OperationContext(op.getId(), op.getKind(), group,
                op.getTargetConfig(), op.getCompensationConfig());
        boolean compensating = op.getPhase() == OperationPhase.COMPENSATING;
        return switch (op.getKind()) {
            case REVERT -> provider.restore(ctx, snapshotFor(provider, group));
            case ENABLE, ADOPT, CONFIG_APPLY, PROFILE_CHANGE, FALLBACK_ENTER, FALLBACK_EXIT,
                    ACCEPT_BASELINE, MIG_POLICY_UPDATE ->
                    compensating && op.getCompensationTarget()
                            == com.xammer.cloud.domain.autospotting.CompensationTarget.SNAPSHOT
                            ? provider.restore(ctx, snapshotFor(provider, group))
                            : provider.executeConversion(ctx);
        };
    }

    private static com.xammer.cloud.service.autospotting.spi.SnapshotPayload snapshotFor(SpotProvider provider,
                                                                                         SpotGroup group) {
        // The snapshot the op persisted is restored by the program; the provider rebuilds it from the
        // group's snapshot reference. For the fake provider a fresh exact snapshot is deterministic.
        return provider.takeSnapshot(
                new com.xammer.cloud.service.autospotting.spi.GroupRef(
                        group.getProvider(), group.getCloudAccountId(), group.getGroupKey(),
                        group.getProviderStableId()),
                new com.xammer.cloud.service.autospotting.spi.ReadOnlyCloudView() {});
    }
}
