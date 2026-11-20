package com.xammer.cloud.service.autospotting.core.ops;

import com.xammer.cloud.repository.autospotting.SpotOperationRepository;
import com.xammer.cloud.service.autospotting.core.SpotOpsLifecycle;
import com.xammer.cloud.service.autospotting.core.lease.LeaseHandle;
import com.xammer.cloud.service.autospotting.core.lease.LeaseRegistry;
import com.xammer.cloud.service.autospotting.core.lease.LeaseService;
import com.xammer.cloud.service.autospotting.core.lease.LeaseUnavailableException;
import com.xammer.cloud.service.autospotting.obs.AutoSpottingMdc;
import com.xammer.cloud.service.autospotting.obs.AutoSpottingMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The per-instance operation dispatcher (design-core.md §4.1, §4.2, §4.7; plan-phase-07.md task
 * 7.1). It polls every 5 s on {@code spotDispatchScheduler} — a dedicated single-thread scheduler no
 * job shares (§4.2), so no job body can delay a claim, a preemption or a revert. Each poll:
 *
 * <ol>
 *   <li>finds groups with a due op (a QUEUED op, or a WAITING op whose {@code next_attempt_at} passed
 *       or whose {@code cancel_requested}/{@code compensate_requested} is set, §4.7);</li>
 *   <li>limited to the free {@code spotOpsExecutor} slots, for each group acquires the group lease
 *       (or reuses its own {@link LeaseRegistry} handle for a WAITING op it already holds, §3.3),
 *       claims the group's first due op {@code RUNNING} in creation order, and submits an
 *       {@link OperationRunner};</li>
 *   <li>a {@link TaskRejectedException} on submit leaves the op QUEUED/WAITING for any instance (the
 *       pool filled between the free-slot check and the submit), and the lease is released.</li>
 * </ol>
 *
 * <p><b>Isolation (ux §9.4).</b> The poll body itself only reads due ops, acquires a lease and
 * submits; it blocks on nothing. Even with every {@code spotOpsExecutor} thread busy and the job
 * queue full, this poll still fires every 5 s (± 1 s), because it runs on {@code spotDispatchScheduler}
 * and submits to a pool it does not wait on.
 *
 * <p><b>Dark.</b> No {@link com.xammer.cloud.service.autospotting.spi.SpotProvider} is registered in
 * production, so a claimed op is parked back by the runner without touching the cloud; and with no
 * op inserted by any caller yet (confirm is Phase 11), the dispatcher finds nothing to do. The 5 s
 * poll is a no-op loop until a later phase inserts ops and registers a provider. No flag enables a
 * cloud-mutating path (plan-phase-07.md "Flag or rollout gate: None").
 */
@Component
public class OperationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OperationDispatcher.class);

    private final SpotOperationRepository operations;
    private final OperationStore store;
    private final OperationRunner runner;
    private final LeaseService leaseService;
    private final LeaseRegistry leaseRegistry;
    private final ThreadPoolTaskExecutor spotOpsExecutor;
    private final SpotOpsLifecycle opsLifecycle;
    private final AutoSpottingMetrics metrics;

    /** Counts the dispatcher polls (ux §9.4 dispatcher-isolation assertion observes this advancing). */
    private final AtomicInteger pollCount = new AtomicInteger();

    public OperationDispatcher(SpotOperationRepository operations, OperationStore store, OperationRunner runner,
                               LeaseService leaseService, LeaseRegistry leaseRegistry,
                               ThreadPoolTaskExecutor spotOpsExecutor, SpotOpsLifecycle opsLifecycle,
                               AutoSpottingMetrics metrics) {
        this.operations = operations;
        this.store = store;
        this.runner = runner;
        this.leaseService = leaseService;
        this.leaseRegistry = leaseRegistry;
        this.spotOpsExecutor = spotOpsExecutor;
        this.opsLifecycle = opsLifecycle;
        this.metrics = metrics;
    }

    /** Polls every 5 s on the dedicated dispatch scheduler (§4.1). Never runs on a job thread. */
    @Scheduled(scheduler = "spotDispatchScheduler", fixedDelay = 5000)
    public void poll() {
        pollCount.incrementAndGet();
        metrics.dispatch("poll");
        if (opsLifecycle.isDraining()) {
            return; // draining: stop claiming new work (§4.4)
        }
        // Tag every poll line with the dispatcher job; per-group context is added in dispatchGroup.
        try (AutoSpottingMdc.Scope ignored = AutoSpottingMdc.builder().job("dispatcher").open()) {
            dispatchDueOps();
        } catch (LeaseUnavailableException storeDown) {
            // The lease store is unreachable; skip this poll. The next one retries (§3.4).
            log.debug("dispatcher poll skipped: lease store unavailable ({})", storeDown.getMessage());
        } catch (RuntimeException e) {
            // A dispatcher poll must never die: log and let the next poll continue (§4.1 isolation).
            log.warn("dispatcher poll error (continuing): {}", e.toString());
        }
    }

    /** The number of polls so far (test observation of the 5 s cadence, ux §9.4). */
    public int pollCount() {
        return pollCount.get();
    }

    private void dispatchDueOps() {
        Instant now = Instant.now();
        List<UUID> groupsWithWork = operations.findGroupsWithDueOps(now);
        if (groupsWithWork.isEmpty()) {
            return;
        }
        for (UUID groupId : groupsWithWork) {
            if (freeSlots() <= 0 || opsLifecycle.isDraining()) {
                return; // no free executor slot this poll; the next poll claims more
            }
            dispatchGroup(groupId, now);
        }
    }

    private void dispatchGroup(UUID groupId, Instant now) {
        try (AutoSpottingMdc.Scope ignored = AutoSpottingMdc.builder().groupId(groupId).open()) {
            dispatchGroupScoped(groupId, now);
        }
    }

    private void dispatchGroupScoped(UUID groupId, Instant now) {
        List<UUID> dueOps = operations.findDueOpIdsForGroup(groupId, now);
        if (dueOps.isEmpty()) {
            return;
        }
        if (!operations.groupRowExists(groupId)) {
            // No group row: the group lease can never be taken, so the op would stay QUEUED forever with no
            // error (D-23-06). Nothing ran and nothing can, so finishing it FAILED needs no lease.
            for (UUID orphan : dueOps) {
                store.finishFailed(orphan, "GROUP_MISSING",
                        "The group this operation targets has no record, so it can't run. Plan again.");
                log.warn("op {} failed: group {} has no spot_group row", orphan, groupId);
            }
            metrics.dispatch("group_missing");
            return;
        }
        UUID opId = dueOps.get(0); // creation order: a preempted queued op ends before its preemptor (§4.7)

        // Reuse our own handle for a WAITING op we already hold (no new acquire, §3.3); else acquire.
        Optional<LeaseHandle> reused = leaseRegistry.find(groupId).filter(h -> !h.isLost());
        LeaseHandle handle = reused.orElse(null);
        boolean acquiredHere = false;
        if (handle == null) {
            Optional<LeaseHandle> acquired = leaseService.acquireGroup(groupId, LeaseService.Component.DISPATCHER);
            if (acquired.isEmpty()) {
                return; // held elsewhere (or locally by another component): leave the op for later
            }
            handle = acquired.get();
            acquiredHere = true;
        }

        if (!store.claimForRun(opId)) {
            // Another instance claimed it between the read and the claim; release if we acquired here.
            if (acquiredHere) {
                leaseService.releaseGroup(handle);
            }
            return;
        }

        LeaseHandle submitHandle = handle;
        boolean releaseAfter = acquiredHere;
        try {
            spotOpsExecutor.execute(() -> runAndRelease(submitHandle, opId, releaseAfter));
            metrics.dispatch("claimed");
        } catch (TaskRejectedException rejected) {
            // Pool filled after the free-slot check (§4.1): put the op back QUEUED, release the lease.
            store.park(opId, com.xammer.cloud.domain.autospotting.WaitReason.BACKOFF, Instant.now(), 0, null);
            if (acquiredHere) {
                leaseService.releaseGroup(handle);
            }
            metrics.dispatch("rejected");
            log.debug("op {} submit rejected: ops executor full; left for another poll", opId);
        }
    }

    private void runAndRelease(LeaseHandle handle, UUID opId, boolean releaseAfter) {
        try {
            runner.runClaimed(handle, opId);
        } catch (RuntimeException e) {
            log.warn("op {} runner error: {}", opId, e.toString());
        } finally {
            if (releaseAfter) {
                leaseService.releaseGroup(handle);
            }
        }
    }

    /** Free {@code spotOpsExecutor} slots right now: pool size minus active workers (§4.1 LIMIT). */
    private int freeSlots() {
        var pool = spotOpsExecutor.getThreadPoolExecutor();
        return Math.max(0, pool.getMaximumPoolSize() - pool.getActiveCount());
    }
}
