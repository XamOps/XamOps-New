package com.xammer.cloud.service.autospotting.core.reconcile;

import com.xammer.cloud.domain.autospotting.CompensationTarget;
import com.xammer.cloud.domain.autospotting.ConfigMode;
import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.OperationKind;
import com.xammer.cloud.domain.autospotting.OperationStatus;
import com.xammer.cloud.domain.autospotting.PauseReason;
import com.xammer.cloud.domain.autospotting.SpotGroup;
import com.xammer.cloud.domain.autospotting.SpotOperation;
import com.xammer.cloud.repository.autospotting.SpotOperationRepository;
import com.xammer.cloud.service.autospotting.core.FallbackBackoff;
import com.xammer.cloud.service.autospotting.core.FallbackCap;
import com.xammer.cloud.service.autospotting.core.FallbackProperties;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter;
import com.xammer.cloud.service.autospotting.core.ops.OperationRecord;
import com.xammer.cloud.service.autospotting.core.ops.OperationStore;
import com.xammer.cloud.service.autospotting.core.ops.PreemptionService;
import com.xammer.cloud.service.autospotting.core.switches.SwitchService;
import com.xammer.cloud.service.autospotting.obs.AutoSpottingMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Inserts the system FALLBACK_ENTER and FALLBACK_EXIT operations the reconciler decides (design-aws.md §6,
 * design-core.md §4.5, §4.7). Provider neutral: the provider's pure {@code reconcile} says that a fallback is
 * called for or that capacity looks back; this class applies the cloud-neutral rules around it.
 *
 * <ul>
 *   <li><b>Enter</b> is SAFETY and always allowed (the daily cap never blocks it). It goes through
 *       {@link PreemptionService}, which refuses it while an op that fallback may not preempt is in flight
 *       and cancels a CONFIG_APPLY, PROFILE_CHANGE or FALLBACK_EXIT after its first mutation.</li>
 *   <li><b>Exit</b> needs the group in FALLBACK with {@code config_mode=FALLBACK}, no op in flight, the dwell and
 *       backoff elapsed, a target ({@code desired_config}) and room under the daily cap
 *       ({@code transitions_today + 2 <= cap}). A group held by the cap is left in FALLBACK and an operator
 *       alert is raised; it exits after 00:00 UTC. The cap never makes a group DEGRADED.</li>
 * </ul>
 *
 * <p>Nothing here calls a cloud. The inserted op is run by the dispatcher, whose provider gate and switch
 * matrix still apply, so with the AWS flags false no fallback mutation can reach AWS.
 */
@Service
public class FallbackEnqueuer {
    private static final Logger log = LoggerFactory.getLogger(FallbackEnqueuer.class);
    /** {@code requested_by} of a fallback op the reconciler inserted. */
    public static final String REQUESTED_BY = "system:fallback";

    /** What an enqueue attempt did. */
    public enum Result { ENQUEUED, BUSY, NOT_ELIGIBLE, TOO_EARLY, CAPPED, NO_TARGET, FROZEN }

    private final SpotOperationRepository operations;
    private final PreemptionService preemption;
    private final OperationStore store;
    private final FallbackProperties props;
    private final AutoSpottingAuditWriter auditWriter;
    private final AutoSpottingMetrics metrics;
    private final SwitchService switches;

    public FallbackEnqueuer(SpotOperationRepository operations, PreemptionService preemption, OperationStore store,
                            FallbackProperties props, AutoSpottingAuditWriter auditWriter,
                            AutoSpottingMetrics metrics, SwitchService switches) {
        this.operations = operations;
        this.preemption = preemption;
        this.store = store;
        this.props = props;
        this.auditWriter = auditWriter;
        this.metrics = metrics;
        this.switches = switches;
    }

    /** True when the group may start a FALLBACK_ENTER from the state it is in (design-aws.md §6.3). */
    public static boolean enterStateAllowed(SpotGroup g) {
        GroupState s = g.getState();
        if (s == GroupState.ACTIVE) {
            return true;
        }
        if (s == GroupState.DEGRADED) {
            return g.getConfigMode() == ConfigMode.MANAGED;
        }
        if (s == GroupState.PAUSED) {
            return (g.getPauseReason() == PauseReason.ACCOUNT_SWITCH || g.getPauseReason() == PauseReason.OPERATOR)
                    && (g.getPausedFromState() == GroupState.ACTIVE || g.getPausedFromState() == GroupState.DEGRADED
                    || g.getPausedFromState() == GroupState.FALLBACK);
        }
        return s == GroupState.APPLYING; // a mutated CONFIG_APPLY, PROFILE_CHANGE or FALLBACK_EXIT (preemption decides)
    }

    /** Enqueue a SAFETY FALLBACK_ENTER, preempting what the rules let it preempt. Never throws for a busy group. */
    @Transactional
    public Result enqueueEnter(SpotGroup group, String reasonCode) {
        if (!enterStateAllowed(group)) {
            return Result.NOT_ELIGIBLE;
        }
        // A group already in FALLBACK (or a PAUSED group already on the fallback mix) has nothing to enter.
        if (group.getState() == GroupState.FALLBACK || group.getConfigMode() == ConfigMode.FALLBACK
                && group.getState() != GroupState.APPLYING) {
            return Result.NOT_ELIGIBLE;
        }
        if (switches.readUncached().freezeEngaged()) {
            // FREEZE blocks every cloud write, fallback included (AC-HLT-05): there is no fallback path.
            metrics.fallback(group.getProvider(), "enter", "frozen");
            return Result.FROZEN;
        }
        UUID opId = UUID.randomUUID();
        PreemptionService.Preemption verdict = preemption.preemptFor(group.getId(), OperationKind.FALLBACK_ENTER, opId);
        if (verdict.verdict() == PreemptionService.Verdict.BUSY) {
            return Result.BUSY;
        }
        store.insertQueued(OperationRecord.builder(opId, group.getId(), group.getCloudAccountId(),
                        OperationKind.FALLBACK_ENTER)
                .priorState(group.getState())
                .priorPauseReason(group.getPauseReason())
                .compensationTarget(CompensationTarget.NONE)
                .requestedBy(REQUESTED_BY).build());
        metrics.fallback(group.getProvider(), "enter", "queued");
        audit("FALLBACK_ENTER_QUEUED", group, opId, reasonCode, verdict.preemptedOpId());
        log.info("group {} FALLBACK_ENTER {} queued ({}, preempted={})", group.getId(), opId, reasonCode,
                verdict.preemptedOpId());
        return Result.ENQUEUED;
    }

    /** Enqueue a FALLBACK_EXIT when the dwell, the backoff and the daily cap allow it. */
    @Transactional
    public Result enqueueExit(SpotGroup group, Instant now) {
        if (group.getState() != GroupState.FALLBACK || group.getConfigMode() != ConfigMode.FALLBACK) {
            return Result.NOT_ELIGIBLE;
        }
        List<SpotOperation> history = operations.findFallbackOpsByGroup(group.getId());
        Instant fallbackSince = group.getStateChangedAt() == null ? now : group.getStateChangedAt();
        Instant due = nextAttemptAt(history, fallbackSince, props.forProvider(group.getProvider()));
        if (now.isBefore(due)) {
            return Result.TOO_EARLY;
        }
        if (group.getDesiredConfig() == null || group.getDesiredConfig().isBlank()) {
            return Result.NO_TARGET; // nothing to return to: the group stays on On-Demand, never guessed
        }
        List<Instant> starts = new ArrayList<>();
        history.forEach(o -> starts.add(o.getCreatedAt()));
        int today = FallbackCap.transitionsToday(starts, now);
        FallbackCap cap = props.forProvider(group.getProvider()).cap();
        if (!cap.exitAllowed(today)) {
            metrics.reconcileAlert(group.getState().name());
            metrics.fallback(group.getProvider(), "exit", "capped");
            log.warn("group {} held in FALLBACK by the daily cap ({} transitions today, cap {}); exit after {}",
                    group.getId(), today, cap.cap(), FallbackCap.nextDayStart(now));
            return Result.CAPPED;
        }
        UUID opId = UUID.randomUUID();
        PreemptionService.Preemption verdict = preemption.preemptFor(group.getId(), OperationKind.FALLBACK_EXIT, opId);
        if (verdict.verdict() == PreemptionService.Verdict.BUSY) {
            return Result.BUSY;
        }
        store.insertQueued(OperationRecord.builder(opId, group.getId(), group.getCloudAccountId(),
                        OperationKind.FALLBACK_EXIT)
                .priorState(GroupState.FALLBACK)
                .compensationTarget(CompensationTarget.FALLBACK)
                .targetConfigJson(group.getDesiredConfig())
                .requestedBy(REQUESTED_BY).build());
        metrics.fallback(group.getProvider(), "exit", "queued");
        audit("FALLBACK_EXIT_QUEUED", group, opId, "CAPACITY_RECOVERED", null);
        return Result.ENQUEUED;
    }

    /** The earliest instant the next exit attempt may start, from the group's fallback history. */
    Instant nextAttemptAt(List<SpotOperation> history, Instant fallbackSince) {
        return nextAttemptAt(history, fallbackSince, props);
    }
    Instant nextAttemptAt(List<SpotOperation> history, Instant fallbackSince, FallbackProperties props) {
        int failed = 0;
        Instant lastFinished = null;
        boolean fastReentry = false;
        Instant lastExitSucceededAt = null;
        Instant lastEnterAt = null;
        for (SpotOperation o : history) {
            if (o.getKind() == OperationKind.FALLBACK_EXIT) {
                if (o.getStatus() == OperationStatus.SUCCEEDED && o.getPhase()
                        == com.xammer.cloud.domain.autospotting.OperationPhase.FORWARD) {
                    lastExitSucceededAt = o.getFinishedAt();
                    failed = 0;
                    lastFinished = null;
                } else if (o.getStatus() == OperationStatus.FAILED || o.getStatus() == OperationStatus.CANCELLED
                        || o.getStatus() == OperationStatus.SUCCEEDED) {
                    // failed, preempted, or compensated back to FALLBACK: the next interval doubles
                    failed++;
                    lastFinished = o.getFinishedAt();
                }
            } else if (o.getKind() == OperationKind.FALLBACK_ENTER) {
                lastEnterAt = o.getCreatedAt();
                if (lastExitSucceededAt != null && lastEnterAt != null
                        && within(lastExitSucceededAt, lastEnterAt, props)) {
                    fastReentry = true;
                } else {
                    fastReentry = false;
                }
                failed = 0;
                lastFinished = null;
            }
        }
        return FallbackBackoff.of(props).nextAttemptAt(fallbackSince, lastFinished, failed, fastReentry);
    }

    private static boolean within(Instant exit, Instant reenter, FallbackProperties props) {
        return !reenter.isBefore(exit) && reenter.isBefore(exit.plus(props.reentryWindow()));
    }

    private void audit(String event, SpotGroup g, UUID opId, String reason, UUID preempted) {
        auditWriter.write(AutoSpottingAuditWriter.AuditRecord.builder(event,
                        AutoSpottingAuditWriter.Actor.system(), "FALLBACK", "QUEUED")
                .operationId(opId)
                .provider(g.getProvider() == null ? null : g.getProvider().name())
                .clientId(g.getClientId())
                .cloudAccountId(g.getCloudAccountId())
                .groupId(g.getId())
                .groupKey(g.getGroupKey())
                .details("{\"reason\":\"" + reason + "\""
                        + (preempted == null ? "" : ",\"preemptedOp\":\"" + preempted + "\"") + "}")
                .build());
    }
}
