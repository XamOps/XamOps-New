package com.xammer.cloud.service.upwind;

import com.xammer.cloud.repository.UpwindSyncLockRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UpwindSyncLeaseTest {

    private UpwindSyncLockRepository lockRepo;
    private UpwindSyncLease lease;

    @BeforeEach
    void setUp() {
        lockRepo = mock(UpwindSyncLockRepository.class);
        lease = new UpwindSyncLease(lockRepo);
    }

    @Test
    void runsTaskWhenLeaseAcquired() {
        when(lockRepo.tryAcquire(eq("FULL"), anyString(), any(), any())).thenReturn(1);
        AtomicInteger runs = new AtomicInteger();

        boolean ran = lease.runIfLeader("FULL", Duration.ofMinutes(5), runs::incrementAndGet);

        assertTrue(ran);
        assertEquals(1, runs.get());
        verify(lockRepo).release(eq("FULL"), eq(lease.holder()), any());
    }

    @Test
    void skipsTaskWhenAnotherInstanceHoldsLease() {
        // The normal case for every ASG instance except the leader.
        when(lockRepo.tryAcquire(eq("FULL"), anyString(), any(), any())).thenReturn(0);
        AtomicInteger runs = new AtomicInteger();

        boolean ran = lease.runIfLeader("FULL", Duration.ofMinutes(5), runs::incrementAndGet);

        assertFalse(ran);
        assertEquals(0, runs.get(), "a non-leader must not sync");
        verify(lockRepo, never()).release(anyString(), anyString(), any());
    }

    @Test
    void releasesLeaseEvenWhenTaskThrows() {
        when(lockRepo.tryAcquire(eq("FULL"), anyString(), any(), any())).thenReturn(1);

        assertThrows(IllegalStateException.class, () ->
                lease.runIfLeader("FULL", Duration.ofMinutes(5), () -> {
                    throw new IllegalStateException("boom");
                }));

        // A crashed run must not hold the lease until expiry.
        verify(lockRepo).release(eq("FULL"), eq(lease.holder()), any());
    }

    @Test
    void stillSyncsWhenLeaseTableUnavailable() {
        // Pre-migration or single-instance deploys must keep syncing rather than stall.
        when(lockRepo.ensureRow(anyString(), any()))
                .thenThrow(new RuntimeException("relation does not exist"));
        AtomicInteger runs = new AtomicInteger();

        boolean ran = lease.runIfLeader("FULL", Duration.ofMinutes(5), runs::incrementAndGet);

        assertTrue(ran);
        assertEquals(1, runs.get());
    }

    @Test
    void acquireIsScopedToTheLeaseNameAndExpiry() {
        when(lockRepo.tryAcquire(anyString(), anyString(), any(), any())).thenReturn(1);
        Instant before = Instant.now();

        lease.runIfLeader("DETECTIONS", Duration.ofMinutes(5), () -> { });

        // Expiry must be in the future, else the lease is free the moment it is taken.
        verify(lockRepo).tryAcquire(eq("DETECTIONS"), eq(lease.holder()),
                argThat(now -> !now.isBefore(before)),
                argThat(exp -> exp.isAfter(before.plusSeconds(240))));
    }

    // --- Mid-run lease ownership, used to gate destructive reconciliation ---

    @Test
    void stillHeldIsTrueWhileTheLeaseIsOurs() {
        when(lockRepo.countHeldBy(eq("FULL"), eq(lease.holder()), any())).thenReturn(1L);
        assertTrue(lease.stillHeld("FULL"));
    }

    @Test
    void stillHeldIsFalseOnceTheLeaseLapsedOrWasTaken() {
        when(lockRepo.countHeldBy(eq("FULL"), eq(lease.holder()), any())).thenReturn(0L);
        assertFalse(lease.stillHeld("FULL"));
    }

    /**
     * Fails CLOSED: the caller uses this to decide whether to archive findings. Skipping an
     * archive pass just leaves stale rows for the next run; archiving without the lease can
     * delete a concurrent run's work.
     */
    @Test
    void stillHeldFailsClosedWhenOwnershipCannotBeConfirmed() {
        when(lockRepo.countHeldBy(anyString(), anyString(), any()))
                .thenThrow(new RuntimeException("relation does not exist"));
        assertFalse(lease.stillHeld("FULL"));
    }

    /** A leased task is handed a guard reflecting real ownership. */
    @Test
    void leasedTaskReceivesLiveOwnershipCheck() {
        when(lockRepo.tryAcquire(eq("FULL"), anyString(), any(), any())).thenReturn(1);
        when(lockRepo.countHeldBy(eq("FULL"), eq(lease.holder()), any()))
                .thenReturn(1L)   // first check: still ours
                .thenReturn(0L);  // later: lapsed
        java.util.List<Boolean> observed = new java.util.ArrayList<>();

        lease.runIfLeader("FULL", Duration.ofMinutes(5), stillLeader -> {
            observed.add(stillLeader.getAsBoolean());
            observed.add(stillLeader.getAsBoolean());
        });

        assertEquals(java.util.List.of(true, false), observed);
    }

    /**
     * With no lease table there is no second instance to collide with, so the guard must be
     * permissive — otherwise reconciliation would be permanently disabled on single-instance
     * and pre-migration deployments.
     */
    @Test
    void leasedTaskIsPermissiveWhenTheLeaseTableIsUnavailable() {
        when(lockRepo.tryAcquire(anyString(), anyString(), any(), any()))
                .thenThrow(new RuntimeException("relation \"upwind_sync_lock\" does not exist"));
        java.util.List<Boolean> observed = new java.util.ArrayList<>();

        boolean ran = lease.runIfLeader("FULL", Duration.ofMinutes(5),
                stillLeader -> observed.add(stillLeader.getAsBoolean()));

        assertTrue(ran, "sync must still run without a lease");
        assertEquals(java.util.List.of(true), observed);
    }
}
