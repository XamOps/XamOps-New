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
}
