package com.xammer.cloud.autospotting.core;

import com.xammer.cloud.service.autospotting.core.SpotOpsLifecycle;
import com.xammer.cloud.service.autospotting.core.lease.LeaseHandle;
import com.xammer.cloud.service.autospotting.core.lease.LeaseRegistry;
import com.xammer.cloud.service.autospotting.core.lease.LeaseService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SpotOpsLifecycle} graceful shutdown (plan-phase-04.md task 4.3, design-core.md §4.4,
 * AC-SPN-09 unit part; the resume part is Phase 07). With a fake in-flight step, {@code stop()}
 * waits for its result then releases the lease; a step still running at the deadline is left for
 * resume (it stays INTENT_RECORDED, which the runner never cleared), and the lease is still
 * released so another instance can take over.
 */
class SpotOpsLifecycleTest {

    @Test
    void stopWaitsForTheInFlightStepThenReleasesTheGroupLease() throws Exception {
        LeaseRegistry registry = new LeaseRegistry();
        LeaseService leaseService = mock(LeaseService.class);
        UUID group = UUID.randomUUID();
        LeaseHandle handle = groupHandle(group);
        when(leaseService.acquireGroup(any(), any())).thenReturn(java.util.Optional.of(handle));
        registry.register(group, handle);

        SpotOpsLifecycle lifecycle = new SpotOpsLifecycle(registry, leaseService, Duration.ofSeconds(5));
        lifecycle.start();

        // A fake in-flight step that records its result only after stop() began draining.
        lifecycle.beginStep();
        CountDownLatch stopReturned = new CountDownLatch(1);
        AtomicBoolean releasedBeforeStepDone = new AtomicBoolean(false);
        CompletableFuture<Void> stop = CompletableFuture.runAsync(() -> {
            lifecycle.stop();
            stopReturned.countDown();
        });

        // Give stop() time to set draining and enter the wait loop.
        Thread.sleep(200);
        assertThat(lifecycle.isDraining()).isTrue();
        // stop() must still be waiting (the step hasn't recorded its result).
        releasedBeforeStepDone.set(stopReturned.getCount() == 0);
        assertThat(releasedBeforeStepDone.get()).isFalse();

        // The step records its result.
        lifecycle.endStep();
        stop.get(5, TimeUnit.SECONDS);

        verify(leaseService).releaseGroup(handle);
        assertThat(lifecycle.isRunning()).isFalse();
    }

    @Test
    void stepStillRunningAtDeadlineIsLeftForResumeButLeaseIsReleased() throws Exception {
        LeaseRegistry registry = new LeaseRegistry();
        LeaseService leaseService = mock(LeaseService.class);
        UUID group = UUID.randomUUID();
        LeaseHandle handle = groupHandle(group);
        registry.register(group, handle);

        // A short grace window so the test doesn't wait long; the step never completes.
        SpotOpsLifecycle lifecycle = new SpotOpsLifecycle(registry, leaseService, Duration.ofMillis(300));
        lifecycle.start();
        lifecycle.beginStep(); // never ended: still in flight at the deadline

        lifecycle.stop();

        // The step was never ended, so it stays "in flight" (INTENT_RECORDED, resolved by probe on
        // resume in Phase 07). The lease is still released so another instance takes over (§4.4).
        assertThat(lifecycle.inFlightSteps()).isEqualTo(1);
        verify(leaseService).releaseGroup(handle);
    }

    @Test
    void stopWithNoHeldLeasesReleasesNothing() {
        LeaseRegistry registry = new LeaseRegistry();
        LeaseService leaseService = mock(LeaseService.class);
        SpotOpsLifecycle lifecycle = new SpotOpsLifecycle(registry, leaseService, Duration.ofMillis(50));
        lifecycle.start();

        lifecycle.stop();

        verify(leaseService, never()).releaseGroup(any());
        assertThat(lifecycle.isDraining()).isTrue();
    }

    @Test
    void lifecyclePhaseStopsBeforeTheWebServer() {
        LeaseRegistry registry = new LeaseRegistry();
        SpotOpsLifecycle lifecycle = new SpotOpsLifecycle(registry, mock(LeaseService.class), Duration.ofSeconds(20));
        // A higher phase stops earlier; AutoSpotting stops before Boot's web server
        // (SmartLifecycle.DEFAULT_PHASE == Integer.MAX_VALUE).
        assertThat(lifecycle.getPhase()).isGreaterThan(Integer.MIN_VALUE);
        assertThat(lifecycle.getPhase()).isLessThan(Integer.MAX_VALUE);
        assertThat(lifecycle.isAutoStartup()).isTrue();
    }

    private static LeaseHandle groupHandle(UUID group) {
        return new LeaseHandle(LeaseHandle.Kind.GROUP, group.toString(),
                "host:dispatcher:" + UUID.randomUUID(), 1L,
                Duration.ofSeconds(120), Duration.ofSeconds(30), Duration.ofSeconds(10), h -> true);
    }
}
