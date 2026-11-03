package com.xammer.cloud.service.autospotting.core.ops;

import com.xammer.cloud.domain.autospotting.GroupState;
import com.xammer.cloud.domain.autospotting.OperationStatus;
import com.xammer.cloud.domain.autospotting.Provider;
import com.xammer.cloud.domain.autospotting.SpotGroup;
import com.xammer.cloud.domain.autospotting.SpotOperation;
import com.xammer.cloud.domain.autospotting.WaitReason;
import com.xammer.cloud.repository.autospotting.SpotGroupRepository;
import com.xammer.cloud.repository.autospotting.SpotOperationRepository;
import com.xammer.cloud.service.autospotting.core.JobRunner;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter.Actor;
import com.xammer.cloud.service.autospotting.core.audit.AutoSpottingAuditWriter.AuditRecord;
import com.xammer.cloud.service.autospotting.core.lease.FencedTx;
import com.xammer.cloud.service.autospotting.core.lease.LeaseHandle;
import com.xammer.cloud.service.autospotting.core.lease.LeaseProperties;
import com.xammer.cloud.service.autospotting.core.lease.LeaseService;
import com.xammer.cloud.service.autospotting.core.lifecycle.GroupLifecycleService;
import com.xammer.cloud.service.autospotting.core.lifecycle.GroupLifecycleService.TransitionRequest;
import com.xammer.cloud.service.autospotting.spi.ProviderRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The startup recovery pass (design-core.md §4.6; plan-phase-07.md task 7.2). On
 * {@link ApplicationReadyEvent}, after {@code autospotting.reconciler.initial-delay} (default 60 s
 * plus up to 30 s of random jitter, so instances started together don't stampede, XC-15), the
 * instance runs one recovery sweep per provider under the reconciler job lease. It is the extra pass
 * that precedes the periodic reconciler (§5, Phase 08), applying the §4.6 table:
 *
 * <ul>
 *   <li>a {@code RUNNING}/{@code WAITING} op whose group lease expired is resumed (probe the last
 *       {@code INTENT_RECORDED} step, then continue) — done by handing the claimed op to the
 *       {@link OperationRunner}, which re-derives the program and re-probes (AC-SPN-06, AC-SPN-09);</li>
 *   <li>an op past {@code deadline_at} gets {@code wait_reason=STUCK} and an alert — never FAILED
 *       just for the cap (GCP-20, AC-SPN-08);</li>
 *   <li>an APPLYING/REVERTING group with no live op waits one dispatcher cycle, then applies the
 *       DR-04 rule ({@code REVERT_NOT_STARTED} back to {@code prior_state} for a REVERT that never
 *       mutated) or the {@code getStatus} match rules, else FAILED. Every resume and rollback writes
 *       an audit record.</li>
 * </ul>
 *
 * <p><b>Dark.</b> With no {@link com.xammer.cloud.service.autospotting.spi.SpotProvider} registered
 * and no op inserted by any caller yet, the sweep finds nothing to resume: it acquires the reconciler
 * job lease, sees no managed group, and releases it. The DR-04 startup rollback is covered by
 * {@code CrashResumeIT} on a fixture, not by any live cloud. No flag enables a cloud-mutating path
 * (plan-phase-07.md "Flag or rollout gate: None").
 */
@Component
public class StartupRecovery {

    private static final Logger log = LoggerFactory.getLogger(StartupRecovery.class);

    static final String JOB_PREFIX = "reconciler:";

    private final SpotOperationRepository operations;
    private final SpotGroupRepository groups;
    private final OperationStore store;
    private final OperationRunner runner;
    private final LeaseService leaseService;
    private final LeaseProperties leaseProperties;
    private final GroupLifecycleService lifecycle;
    private final FencedTx fencedTx;
    private final AutoSpottingAuditWriter auditWriter;
    private final ProviderRegistry providers;
    private final ThreadPoolTaskScheduler spotTaskScheduler;
    private final Duration initialDelay;
    private final Duration jitter;

    /** Counts completed sweeps (test observation; a sweep ran even when it found nothing, §4.6). */
    private final AtomicInteger sweepCount = new AtomicInteger();
    private volatile boolean shuttingDown;

    public StartupRecovery(SpotOperationRepository operations, SpotGroupRepository groups, OperationStore store,
                           OperationRunner runner, LeaseService leaseService, LeaseProperties leaseProperties,
                           GroupLifecycleService lifecycle, FencedTx fencedTx,
                           AutoSpottingAuditWriter auditWriter, ProviderRegistry providers,
                           ThreadPoolTaskScheduler spotTaskScheduler,
                           @Value("${autospotting.reconciler.initial-delay:60s}") Duration initialDelay,
                           @Value("${autospotting.reconciler.initial-delay-jitter:30s}") Duration jitter) {
        this.operations = operations;
        this.groups = groups;
        this.store = store;
        this.runner = runner;
        this.leaseService = leaseService;
        this.leaseProperties = leaseProperties;
        this.lifecycle = lifecycle;
        this.fencedTx = fencedTx;
        this.auditWriter = auditWriter;
        this.providers = providers;
        this.spotTaskScheduler = spotTaskScheduler;
        this.initialDelay = initialDelay;
        this.jitter = jitter;
    }

    /** Schedule the one-shot recovery sweep after the jittered initial delay (§4.6, XC-15). */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        long jitterMillis = jitter.isZero() ? 0 : ThreadLocalRandom.current().nextLong(jitter.toMillis() + 1);
        Instant when = Instant.now().plus(initialDelay).plusMillis(jitterMillis);
        spotTaskScheduler.schedule(this::runSweepSafely, when);
        if (log.isDebugEnabled()) {
            log.debug("AutoSpotting startup recovery scheduled at {} (delay {} + jitter {}ms)",
                    when, initialDelay, jitterMillis);
        }
    }

    @EventListener(ContextClosedEvent.class)
    public void onClose() {
        this.shuttingDown = true;
    }

    /** The number of recovery sweeps run (test observation, §4.6). */
    public int sweepCount() {
        return sweepCount.get();
    }

    private void runSweepSafely() {
        if (shuttingDown) {
            return;
        }
        try {
            runSweep();
        } catch (RuntimeException e) {
            log.warn("AutoSpotting startup recovery sweep error (will retry on the next reconciler run): {}",
                    e.toString());
        }
    }

    /** One recovery sweep per provider, each under the reconciler job lease (§4.6, FR-SPN-08). */
    public void runSweep() {
        for (Provider provider : Provider.values()) {
            String job = JOB_PREFIX + provider.name().toLowerCase(java.util.Locale.ROOT);
            Optional<LeaseHandle> lease = leaseService.acquireJob(job,
                    leaseProperties.reconcilerTtl(), leaseProperties.reconcilerHeartbeat());
            if (lease.isEmpty()) {
                continue; // another instance owns this provider's reconciler lease
            }
            LeaseHandle handle = lease.get();
            try {
                recoverProvider(provider);
            } finally {
                leaseService.releaseJob(handle, Duration.ZERO, null);
            }
        }
        sweepCount.incrementAndGet();
    }

    private void recoverProvider(Provider provider) {
        // Resume live ops whose group lease expired, and mark past-deadline ops STUCK (§4.6).
        for (SpotGroup group : groups.findByProviderAndState(provider, GroupState.APPLYING)) {
            recoverGroup(group);
        }
        for (SpotGroup group : groups.findByProviderAndState(provider, GroupState.REVERTING)) {
            recoverGroup(group);
        }
    }

    /** Apply the §4.6 table for one APPLYING/REVERTING group. Public so the recovery ITs drive it. */
    public void recoverGroup(SpotGroup group) {
        List<SpotOperation> live = operations.findLiveByGroup(group.getId());
        if (!live.isEmpty()) {
            resumeOrMarkStuck(group, live.get(0));
            return;
        }
        // No live op for an APPLYING/REVERTING group (§4.6 last row): the DR-04 rule or FAILED.
        handleNoLiveOp(group);
    }

    private void resumeOrMarkStuck(SpotGroup group, SpotOperation op) {
        // Acquire the group lease. Empty means another instance holds it live and is driving the op
        // (§4.6 "lease live" → skip). A past-deadline op is marked STUCK; an expired-lease op resumes.
        Optional<LeaseHandle> lease = leaseService.acquireGroup(group.getId(), LeaseService.Component.RECONCILER);
        if (lease.isEmpty()) {
            return;
        }
        LeaseHandle handle = lease.get();
        try {
            if (op.getDeadlineAt() != null && op.getDeadlineAt().isBefore(Instant.now())) {
                store.markStuck(op.getId());
                audit(handle, group, op, "OP_STUCK", "STUCK", "DEADLINE_EXCEEDED");
                if (log.isDebugEnabled()) {
                    log.debug("op {} past deadline: marked STUCK (not FAILED, GCP-20)", op.getId());
                }
                return;
            }
            if (store.claimForRun(op.getId())) {
                audit(handle, group, op, "OP_RESUMED", "RESUMED", "LEASE_EXPIRED");
                runner.runClaimed(handle, op.getId()); // re-derives program, probes INTENT_RECORDED (§4.5)
            }
        } finally {
            leaseService.releaseGroup(handle);
        }
    }

    /**
     * An APPLYING/REVERTING group with no non-terminal op (§4.6): apply the DR-04 rule for a REVERT
     * that never mutated (back to {@code prior_state} with {@code REVERT_NOT_STARTED}), else leave the
     * group for the periodic reconciler's {@code getStatus} match (Phase 08). FAILED only when the
     * status cannot be matched, which needs a provider and so is dark this phase.
     */
    private void handleNoLiveOp(SpotGroup group) {
        Optional<SpotOperation> latest = operations.findLatestByGroup(group.getId());
        if (latest.isEmpty()) {
            return;
        }
        SpotOperation op = latest.get();
        boolean revertNotStarted = op.getKind() == com.xammer.cloud.domain.autospotting.OperationKind.REVERT
                && op.getFirstMutationSeq() == null
                && group.getState() == GroupState.REVERTING;
        if (revertNotStarted) {
            Optional<LeaseHandle> lease = leaseService.acquireGroup(group.getId(),
                    LeaseService.Component.RECONCILER);
            if (lease.isEmpty()) {
                return;
            }
            LeaseHandle handle = lease.get();
            try {
                lifecycle.transition(handle, reread(group),
                        TransitionRequest.of(op.getPriorState(), "REVERT_NOT_STARTED", Actor.system(),
                                op.getId(), Instant.now()));
                audit(handle, group, op, "GROUP_ROLLBACK", "ROLLED_BACK", "REVERT_NOT_STARTED");
                if (log.isDebugEnabled()) {
                    log.debug("group {} REVERTING with un-mutated REVERT {}: back to {} (DR-04)",
                            group.getId(), op.getId(), op.getPriorState());
                }
            } catch (IllegalArgumentException forbidden) {
                log.warn("group {} DR-04 rollback {} -> {} not allowed: {}", group.getId(),
                        group.getState(), op.getPriorState(), forbidden.getMessage());
            } finally {
                leaseService.releaseGroup(handle);
            }
        }
        // Otherwise: a provider getStatus match decides forward/compensation/FAILED (Phase 08). Dark here.
    }

    private SpotGroup reread(SpotGroup group) {
        return groups.findById(group.getId()).orElse(group);
    }

    /** Write a system audit record fenced on {@code handle} (§4.6 "Every resume and rollback writes an audit record"). */
    private void audit(LeaseHandle handle, SpotGroup group, SpotOperation op, String eventType, String outcome,
                       String reason) {
        fencedTx.run(handle, () -> auditWriter.write(
                AuditRecord.builder(eventType, Actor.system(), op.getKind().name(), outcome)
                        .operationId(op.getId())
                        .provider(group.getProvider() == null ? null : group.getProvider().name())
                        .clientId(group.getClientId())
                        .cloudAccountId(group.getCloudAccountId())
                        .groupId(group.getId())
                        .groupKey(group.getGroupKey())
                        .details(reason == null ? null : "{\"reason\":\"" + reason + "\"}")
                        .build()));
    }
}
