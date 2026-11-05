package com.xammer.cloud.service.autospotting.core;

import com.xammer.cloud.service.autospotting.core.lease.LeaseHandle;
import com.xammer.cloud.service.autospotting.core.lease.LeaseRegistry;
import com.xammer.cloud.service.autospotting.core.lease.LeaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Graceful shutdown of the AutoSpotting operation machinery (design-core.md §4.4, §3.6,
 * plan-phase-04.md task 4.3, AC-SPN-09). A {@link SmartLifecycle} with a phase above the web server
 * and the executors, so it stops <em>before</em> them: it marks the service draining, waits for
 * in-flight steps to record their result, then releases the group leases so another instance resumes
 * immediately.
 *
 * <p>Graceful shutdown is an optimization; correctness never depends on it. A hard kill is already
 * covered by the write-ahead steps plus lease expiry within the TTL (§3.6). The runner that owns a
 * step (a later phase) registers it with {@link #beginStep()} / {@link #endStep()} and must start no
 * new step once {@link #isDraining()} is true; a step still in flight at the grace deadline stays
 * {@code INTENT_RECORDED} and is resolved by a probe on resume (the resume part is Phase 07).
 *
 * <p>Phase order: the lifecycle sequence is
 * <ol>
 *   <li>set {@code draining=true} (dispatchers stop claiming, runners start no new step);</li>
 *   <li>wait up to {@code autospotting.shutdown.grace} (default 20 s) for in-flight steps to drain;</li>
 *   <li>release every group lease this instance holds ({@code expires_at=clock_timestamp()}), so
 *       another instance takes over at once. Job leases are released by their {@code JobRunner}
 *       bodies as they finish; daily jobs keep their minimum hold (§3.5).</li>
 * </ol>
 */
@Component
public class SpotOpsLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(SpotOpsLifecycle.class);

    /**
     * Stop before the web server (Boot's web server stops at
     * {@code WebServerStartStopLifecycle} around {@link SmartLifecycle#DEFAULT_PHASE}) and before the
     * executors, so draining and lease release happen while steps can still record. A higher phase
     * stops earlier.
     */
    static final int LIFECYCLE_PHASE = Integer.MAX_VALUE - 1024;

    private static final long POLL_MILLIS = 100;

    private final LeaseRegistry leaseRegistry;
    private final LeaseService leaseService;
    private final Duration grace;

    private volatile boolean running;
    private volatile boolean draining;
    private final AtomicInteger inFlightSteps = new AtomicInteger();

    public SpotOpsLifecycle(LeaseRegistry leaseRegistry, LeaseService leaseService,
                            @Value("${autospotting.shutdown.grace:20s}") Duration grace) {
        this.leaseRegistry = leaseRegistry;
        this.leaseService = leaseService;
        this.grace = grace;
    }

    /** True once shutdown has begun: dispatchers stop claiming and runners start no new step. */
    public boolean isDraining() {
        return draining;
    }

    /** Count of steps currently recording a result; shutdown waits for this to reach zero. */
    public int inFlightSteps() {
        return inFlightSteps.get();
    }

    /** A runner calls this when a step begins (after it passed the drain check). */
    public void beginStep() {
        inFlightSteps.incrementAndGet();
    }

    /** A runner calls this when a step has recorded its result. */
    public void endStep() {
        inFlightSteps.decrementAndGet();
    }

    @Override
    public void start() {
        this.draining = false;
        this.running = true;
    }

    @Override
    public void stop() {
        this.draining = true;
        long remaining = waitForInFlightSteps(grace.toMillis());
        int stillRunning = inFlightSteps.get();
        if (stillRunning > 0) {
            log.warn("AutoSpotting graceful shutdown: {} step(s) still in flight at the {}s deadline; "
                            + "they stay INTENT_RECORDED and resume on another instance",
                    stillRunning, grace.toSeconds());
        } else {
            log.info("AutoSpotting graceful shutdown: in-flight steps drained with {}ms of grace to spare",
                    remaining);
        }
        releaseGroupLeases();
        this.running = false;
    }

    /** Waits up to {@code budgetMillis} for in-flight steps to drain; returns the budget left. */
    private long waitForInFlightSteps(long budgetMillis) {
        long deadline = System.nanoTime() + Duration.ofMillis(budgetMillis).toNanos();
        while (inFlightSteps.get() > 0) {
            long leftNanos = deadline - System.nanoTime();
            if (leftNanos <= 0) {
                return 0;
            }
            try {
                Thread.sleep(Math.min(POLL_MILLIS, Duration.ofNanos(leftNanos).toMillis() + 1));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Math.max(0, Duration.ofNanos(deadline - System.nanoTime()).toMillis());
            }
        }
        return Math.max(0, Duration.ofNanos(deadline - System.nanoTime()).toMillis());
    }

    /** Releases every group lease this instance holds so another instance takes over at once. */
    private void releaseGroupLeases() {
        Map<UUID, LeaseHandle> held = leaseRegistry.snapshot();
        if (held.isEmpty()) {
            return;
        }
        log.info("AutoSpotting graceful shutdown: releasing {} group lease(s)", held.size());
        for (LeaseHandle handle : held.values()) {
            try {
                leaseService.releaseGroup(handle);
            } catch (RuntimeException e) {
                log.warn("AutoSpotting graceful shutdown: could not release group lease {}: {}",
                        handle.resourceId(), e.toString());
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return LIFECYCLE_PHASE;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }
}
