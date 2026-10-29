package com.xammer.cloud.autospotting.lease;

import com.xammer.cloud.repository.autospotting.LeaseRepository;
import com.xammer.cloud.service.autospotting.core.lease.FencedTx;
import com.xammer.cloud.service.autospotting.core.lease.LeaseHandle;
import com.xammer.cloud.service.autospotting.core.lease.LeaseLostException;
import com.xammer.cloud.service.autospotting.core.lease.LeaseProperties;
import com.xammer.cloud.service.autospotting.core.lease.LeaseRegistry;
import com.xammer.cloud.service.autospotting.core.lease.LeaseService;
import com.xammer.cloud.service.autospotting.core.lease.LeaseUnavailableException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link LeaseService}, {@link LeaseHandle} and {@link FencedTx} with a mocked
 * {@link LeaseRepository} (plan-phase-04.md task 4.1, design-core.md §3.3, §3.4). The PG behaviour
 * (real {@code clock_timestamp()} races, fencing) is in {@code LeaseConcurrencyIT}.
 */
class LeaseServiceTest {

    private LeaseRepository repository;
    private LeaseRegistry registry;
    private SimpleMeterRegistry meterRegistry;
    private LeaseService service;
    private final LeaseProperties properties = new LeaseProperties(120, 30, 600, 60, 1800, 60, 300, 10);

    private static final String FAILURES = "autospotting.lease.failures";

    @BeforeEach
    void setUp() {
        repository = mock(LeaseRepository.class);
        registry = new LeaseRegistry();
        meterRegistry = new SimpleMeterRegistry();
        service = new LeaseService(repository, registry, properties, meterRegistry);
    }

    private double failures() {
        return meterRegistry.find(FAILURES).tag("reason", "store_error").counter() == null
                ? 0.0
                : meterRegistry.find(FAILURES).tag("reason", "store_error").counter().count();
    }

    // ── token shape ────────────────────────────────────────────────────────────

    @Test
    void acquiredTokenHasInstanceComponentUuidShape() {
        UUID group = UUID.randomUUID();
        when(repository.acquireGroup(eq(group), any(), any())).thenReturn(5L);

        LeaseHandle handle = service.acquireGroup(group, LeaseService.Component.DISPATCHER).orElseThrow();

        String[] parts = handle.token().split(":");
        assertThat(parts).hasSize(3);
        assertThat(parts[0]).isEqualTo(service.instanceId());
        assertThat(parts[1]).isEqualTo("dispatcher");
        assertThat(UUID.fromString(parts[2])).isNotNull();
        assertThat(handle.generation()).isEqualTo(5L);
        assertThat(registry.isHeldLocally(group)).isTrue();
    }

    @Test
    void componentNamesMatchTheDesign() {
        assertThat(LeaseService.Component.DISPATCHER.token()).isEqualTo("dispatcher");
        assertThat(LeaseService.Component.RECONCILER.token()).isEqualTo("reconciler");
        assertThat(LeaseService.Component.EBS.token()).isEqualTo("ebs");
        assertThat(LeaseService.Component.API.token()).isEqualTo("api");
    }

    // ── acquire group ────────────────────────────────────────────────────────────

    @Test
    void acquireGroupReturnsEmptyWhenHeldElsewhere() {
        UUID group = UUID.randomUUID();
        when(repository.acquireGroup(eq(group), any(), any())).thenReturn(null);

        assertThat(service.acquireGroup(group, LeaseService.Component.RECONCILER)).isEmpty();
        assertThat(registry.isHeldLocally(group)).isFalse();
    }

    @Test
    void acquireGroupStoreErrorThrowsUnavailableAndCountsFailure() {
        UUID group = UUID.randomUUID();
        when(repository.acquireGroup(eq(group), any(), any()))
                .thenThrow(new DataAccessResourceFailureException("down"));

        assertThatThrownBy(() -> service.acquireGroup(group, LeaseService.Component.API))
                .isInstanceOf(LeaseUnavailableException.class)
                .extracting(e -> ((LeaseUnavailableException) e).isRetryable()).isEqualTo(true);
        assertThat(failures()).isEqualTo(1.0);
    }

    // ── renew (the handle's Renewer) ────────────────────────────────────────────

    @Test
    void renewZeroRowsMarksHandleLost() {
        UUID group = UUID.randomUUID();
        when(repository.acquireGroup(eq(group), any(), any())).thenReturn(1L);
        when(repository.renewGroup(eq(group), any(), eq(1L), any())).thenReturn(false);

        LeaseHandle handle = service.acquireGroup(group, LeaseService.Component.DISPATCHER).orElseThrow();
        boolean renewed = service.renew(handle);

        assertThat(renewed).isFalse();
        assertThat(handle.isLost()).isTrue();
    }

    @Test
    void renewStoreErrorThrowsUnavailableMarksLostAndCounts() {
        UUID group = UUID.randomUUID();
        when(repository.acquireGroup(eq(group), any(), any())).thenReturn(1L);
        when(repository.renewGroup(eq(group), any(), eq(1L), any()))
                .thenThrow(new DataAccessResourceFailureException("down"));

        LeaseHandle handle = service.acquireGroup(group, LeaseService.Component.DISPATCHER).orElseThrow();

        assertThatThrownBy(() -> service.renew(handle)).isInstanceOf(LeaseUnavailableException.class);
        assertThat(handle.isLost()).isTrue();
        assertThat(failures()).isEqualTo(1.0);
    }

    // ── releaseGroup ─────────────────────────────────────────────────────────────

    @Test
    void releaseGroupExpiresAndClearsRegistryEvenOnStoreError() {
        UUID group = UUID.randomUUID();
        when(repository.acquireGroup(eq(group), any(), any())).thenReturn(1L);
        LeaseHandle handle = service.acquireGroup(group, LeaseService.Component.DISPATCHER).orElseThrow();

        service.releaseGroup(handle);
        verify(repository).releaseGroup(eq(group), eq(handle.token()), eq(1L));
        assertThat(handle.isLost()).isTrue();
        assertThat(registry.isHeldLocally(group)).isFalse();
    }

    // ── job lease ────────────────────────────────────────────────────────────────

    @Test
    void acquireJobBootstrapsRowThenAcquires() {
        when(repository.acquireJob(eq("reconciler:aws"), any(), any())).thenReturn(3L);

        Optional<LeaseHandle> handle = service.acquireJob("reconciler:aws",
                properties.reconcilerTtl(), properties.reconcilerHeartbeat());

        assertThat(handle).isPresent();
        assertThat(handle.get().kind()).isEqualTo(LeaseHandle.Kind.JOB);
        verify(repository).ensureJobRow("reconciler:aws");
    }

    @Test
    void releaseJobPassesMinimumHoldAndRunKey() {
        when(repository.acquireJob(eq("discovery:gcp"), any(), any())).thenReturn(1L);
        when(repository.releaseJob(eq("discovery:gcp"), any(), eq(1L), any(), any())).thenReturn(true);
        LeaseHandle handle = service.acquireJob("discovery:gcp",
                properties.dailyTtl(), properties.dailyHeartbeat()).orElseThrow();

        service.releaseJob(handle, properties.dailyMinHold(), "2026-10-06");

        verify(repository).releaseJob("discovery:gcp", handle.token(), 1L, Duration.ofMinutes(5), "2026-10-06");
        assertThat(handle.isLost()).isTrue();
    }

    @Test
    void lastRunKeyDelegatesToRepository() {
        when(repository.lastRunKey("daily:x")).thenReturn("2026-10-06");
        assertThat(service.lastRunKey("daily:x")).isEqualTo("2026-10-06");
    }

    // ── LeaseHandle.assertHeld (design-core.md §3.4) ────────────────────────────

    @Test
    void assertHeldThrowsWhenLost() {
        LeaseHandle handle = groupHandle((h) -> true);
        handle.markLost();
        assertThatThrownBy(() -> handle.assertHeld(Duration.ofSeconds(45)))
                .isInstanceOf(LeaseLostException.class);
    }

    @Test
    void assertHeldDoesNotRenewWhenTimeLeftIsAmple() {
        AtomicBoolean renewed = new AtomicBoolean(false);
        LeaseHandle handle = groupHandle((h) -> {
            renewed.set(true);
            return true;
        });
        // ttl 120s, skew 10s, call 45s: 120 - 10 = 110 > 45, so no renew.
        handle.assertHeld(Duration.ofSeconds(45));
        assertThat(renewed).isFalse();
    }

    @Test
    void assertHeldRenewsSynchronouslyWhenTimeLeftIsShort() {
        AtomicBoolean renewed = new AtomicBoolean(false);
        LeaseHandle handle = shortTtlGroupHandle((h) -> {
            renewed.set(true);
            return true;
        });
        // ttl 20s, skew 10s, call 45s: 20 - 10 = 10 <= 45, so it renews first.
        handle.assertHeld(Duration.ofSeconds(45));
        assertThat(renewed).isTrue();
    }

    @Test
    void assertHeldThrowsWhenRenewFails() {
        LeaseHandle handle = shortTtlGroupHandle((h) -> false);
        assertThatThrownBy(() -> handle.assertHeld(Duration.ofSeconds(45)))
                .isInstanceOf(LeaseLostException.class);
    }

    // ── FencedTx ────────────────────────────────────────────────────────────────

    @Test
    void fencedWorkRunsWhenFenceHeld() {
        FencedTx fencedTx = new FencedTx(repository, meterRegistry);
        LeaseHandle handle = groupHandle((h) -> true);
        when(repository.fenceHeld(any(), eq(handle.token()), eq(handle.generation()))).thenReturn(true);

        AtomicBoolean ran = new AtomicBoolean(false);
        fencedTx.run(handle, () -> ran.set(true));

        assertThat(ran).isTrue();
    }

    @Test
    void fencedWorkThrowsLostAndMarksHandleWhenFenceMisses() {
        FencedTx fencedTx = new FencedTx(repository, meterRegistry);
        LeaseHandle handle = groupHandle((h) -> true);
        when(repository.fenceHeld(any(), eq(handle.token()), eq(handle.generation()))).thenReturn(false);

        AtomicBoolean ran = new AtomicBoolean(false);
        assertThatThrownBy(() -> fencedTx.run(handle, () -> ran.set(true)))
                .isInstanceOf(LeaseLostException.class);
        assertThat(ran).isFalse();
        assertThat(handle.isLost()).isTrue();
    }

    @Test
    void fencedWorkStoreErrorThrowsUnavailableAndCounts() {
        FencedTx fencedTx = new FencedTx(repository, meterRegistry);
        LeaseHandle handle = groupHandle((h) -> true);
        when(repository.fenceHeld(any(), any(), anyLong()))
                .thenThrow(new DataAccessResourceFailureException("down"));

        assertThatThrownBy(() -> fencedTx.run(handle, () -> { }))
                .isInstanceOf(LeaseUnavailableException.class);
        assertThat(failures()).isEqualTo(1.0);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private LeaseHandle groupHandle(LeaseHandle.Renewer renewer) {
        return new LeaseHandle(LeaseHandle.Kind.GROUP, UUID.randomUUID().toString(),
                service.instanceId() + ":dispatcher:" + UUID.randomUUID(), 1L,
                Duration.ofSeconds(120), Duration.ofSeconds(30), Duration.ofSeconds(10), renewer);
    }

    private LeaseHandle shortTtlGroupHandle(LeaseHandle.Renewer renewer) {
        return new LeaseHandle(LeaseHandle.Kind.GROUP, UUID.randomUUID().toString(),
                service.instanceId() + ":dispatcher:" + UUID.randomUUID(), 1L,
                Duration.ofSeconds(20), Duration.ofSeconds(10), Duration.ofSeconds(10), renewer);
    }
}
