package com.xammer.cloud.service.autospotting.core.lease;

import com.xammer.cloud.repository.autospotting.LeaseRepository;
import com.xammer.cloud.service.autospotting.obs.AutoSpottingMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Acquire, heartbeat and release of the group and job leases (design-core.md §3.3, §3.4, §3.5).
 * Correctness is the guarded native statements in {@link LeaseRepository}; this service mints the
 * per-acquisition token, maps a store error to {@link LeaseUnavailableException} (counting
 * {@code autospotting.lease.failures{reason=store_error}}, AC-SPN-02), records group handles in the
 * {@link LeaseRegistry}, and marks a handle LOST when a renew updates zero rows.
 *
 * <p>The holder token is {@code <instanceId>:<component>:<uuid>}, minted per acquisition
 * (design-core.md §3.3). Acquire is <strong>not</strong> re-entrant: the dispatcher, reconciler,
 * confirm path and EBS automation on one instance contend exactly like two instances. The components
 * are {@code dispatcher}, {@code reconciler}, {@code ebs} and {@code api}.
 */
@Service
public class LeaseService {

    private static final Logger log = LoggerFactory.getLogger(LeaseService.class);

    /** The components allowed in a holder token (design-core.md §3.3). */
    public enum Component {
        DISPATCHER("dispatcher"),
        RECONCILER("reconciler"),
        EBS("ebs"),
        API("api");

        private final String token;

        Component(String token) {
            this.token = token;
        }

        public String token() {
            return token;
        }
    }

    private final LeaseRepository leases;
    private final LeaseRegistry registry;
    private final LeaseProperties properties;
    private final AutoSpottingMetrics metrics;
    private final String instanceId;

    public LeaseService(LeaseRepository leases, LeaseRegistry registry, LeaseProperties properties,
                        AutoSpottingMetrics metrics) {
        this.leases = leases;
        this.registry = registry;
        this.properties = properties;
        this.metrics = metrics;
        this.instanceId = resolveInstanceId();
    }

    private static String resolveInstanceId() {
        String fromEnv = System.getenv("HOSTNAME");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "instance-" + UUID.randomUUID().toString().substring(0, 8);
        }
    }

    /** The {@code instanceId} part of every token minted here. Same-instance components still contend. */
    public String instanceId() {
        return instanceId;
    }

    private String mintToken(Component component) {
        return instanceId + ":" + component.token() + ":" + UUID.randomUUID();
    }

    // ── group lease ────────────────────────────────────────────────────────────

    /**
     * Try to acquire the group lease for {@code component}. Returns a registered {@link LeaseHandle}
     * when this instance won, or empty when the lease is held (locally or elsewhere). Throws
     * {@link LeaseUnavailableException} when the store is unreachable.
     */
    public Optional<LeaseHandle> acquireGroup(UUID groupId, Component component) {
        String token = mintToken(component);
        Duration ttl = properties.groupTtl();
        Long generation;
        try {
            generation = leases.acquireGroup(groupId, token, ttl);
        } catch (RuntimeException e) {
            throw storeUnavailable("acquire group lease " + groupId, e);
        }
        if (generation == null) {
            return Optional.empty();
        }
        LeaseHandle handle = new LeaseHandle(LeaseHandle.Kind.GROUP, groupId.toString(), token, generation,
                ttl, properties.groupHeartbeat(), properties.skewMargin(), this::renew);
        registry.register(groupId, handle);
        log.debug("Acquired group lease {} as {} gen={}", groupId, token, generation);
        return Optional.of(handle);
    }

    /** Release a group lease and drop it from the registry. A store error is logged, not thrown. */
    public void releaseGroup(LeaseHandle handle) {
        requireGroup(handle);
        UUID groupId = UUID.fromString(handle.resourceId());
        try {
            leases.releaseGroup(groupId, handle.token(), handle.generation());
        } catch (RuntimeException e) {
            if (!isStoreError(e)) {
                throw e;
            }
            // Release is best-effort: the lease will expire within its TTL anyway (§3.6).
            metrics.leaseFailure("store_error");
            log.warn("Could not release group lease {} ({}): {}", groupId, handle.token(), e.toString());
        } finally {
            handle.markLost();
            registry.remove(groupId);
        }
    }

    // ── job lease ───────────────────────────────────────────────────────────────

    /** Bootstrap the row for a job name (INSERT ... ON CONFLICT DO NOTHING). */
    public void ensureJob(String name) {
        try {
            leases.ensureJobRow(name);
        } catch (RuntimeException e) {
            throw storeUnavailable("bootstrap job lease " + name, e);
        }
    }

    /**
     * Try to acquire a job lease with {@code ttl} and {@code heartbeat}. Bootstraps the row first.
     * Returns a {@link LeaseHandle} when won (job handles are not kept in the group registry), empty
     * when held elsewhere or still inside its minimum hold (§3.5).
     */
    public Optional<LeaseHandle> acquireJob(String name, Duration ttl, Duration heartbeat) {
        ensureJob(name);
        String token = mintToken(Component.DISPATCHER);
        Long generation;
        try {
            generation = leases.acquireJob(name, token, ttl);
        } catch (RuntimeException e) {
            throw storeUnavailable("acquire job lease " + name, e);
        }
        if (generation == null) {
            return Optional.empty();
        }
        LeaseHandle handle = new LeaseHandle(LeaseHandle.Kind.JOB, name, token, generation, ttl, heartbeat,
                properties.skewMargin(), this::renew);
        log.debug("Acquired job lease {} as {} gen={}", name, token, generation);
        return Optional.of(handle);
    }

    /** The job's recorded {@code last_run_key}, used to skip a re-run within the day (§3.5). */
    public String lastRunKey(String name) {
        try {
            return leases.lastRunKey(name);
        } catch (RuntimeException e) {
            throw storeUnavailable("read last_run_key for " + name, e);
        }
    }

    /**
     * Release a job lease, applying the minimum hold and recording {@code runKey} (null keeps the
     * stored value). A store error is logged, not thrown.
     */
    public void releaseJob(LeaseHandle handle, Duration minHold, String runKey) {
        requireJob(handle);
        try {
            leases.releaseJob(handle.resourceId(), handle.token(), handle.generation(), minHold, runKey);
        } catch (RuntimeException e) {
            if (!isStoreError(e)) {
                throw e;
            }
            metrics.leaseFailure("store_error");
            log.warn("Could not release job lease {} ({}): {}", handle.resourceId(), handle.token(), e.toString());
        } finally {
            handle.markLost();
        }
    }

    // ── heartbeat (the handle's Renewer) ────────────────────────────────────────

    /**
     * Renew a handle. On success stamps its monotonic clock and returns true; a zero-row update marks
     * the handle LOST and returns false; a store error marks it LOST, counts the failure and throws
     * {@link LeaseUnavailableException}. Wired as the {@link LeaseHandle.Renewer}.
     */
    public boolean renew(LeaseHandle handle) {
        boolean renewed;
        try {
            renewed = handle.kind() == LeaseHandle.Kind.GROUP
                    ? leases.renewGroup(UUID.fromString(handle.resourceId()), handle.token(), handle.generation(),
                            handle.ttl())
                    : leases.renewJob(handle.resourceId(), handle.token(), handle.generation(), handle.ttl());
        } catch (RuntimeException e) {
            handle.markLost();
            throw storeUnavailable("renew lease " + handle.resourceId(), e);
        }
        if (renewed) {
            handle.markRenewed();
        } else {
            handle.markLost();
            log.warn("Lease {} ({}) lost: renew updated no rows", handle.resourceId(), handle.token());
        }
        return renewed;
    }

    /**
     * A store error (the DB is unreachable, or a transaction couldn't begin) maps to
     * {@link LeaseUnavailableException} and counts {@code store_error} (AC-SPN-02). A
     * {@link LeaseUnavailableException} already produced upstream (e.g. by a nested call) is passed
     * through unwrapped so it counts once. Any other {@link RuntimeException} is rethrown unchanged.
     */
    private RuntimeException storeUnavailable(String what, RuntimeException cause) {
        if (cause instanceof LeaseUnavailableException already) {
            return already;
        }
        if (!isStoreError(cause)) {
            return cause;
        }
        metrics.leaseFailure("store_error");
        return new LeaseUnavailableException("lease store unreachable: " + what, cause);
    }

    /** A DB access failure or a transaction that couldn't begin is a store error (§3.4). */
    static boolean isStoreError(Throwable t) {
        return t instanceof DataAccessException || t instanceof TransactionException;
    }

    private static void requireGroup(LeaseHandle handle) {
        if (handle.kind() != LeaseHandle.Kind.GROUP) {
            throw new IllegalArgumentException("expected a GROUP handle, got " + handle.kind());
        }
    }

    private static void requireJob(LeaseHandle handle) {
        if (handle.kind() != LeaseHandle.Kind.JOB) {
            throw new IllegalArgumentException("expected a JOB handle, got " + handle.kind());
        }
    }
}
