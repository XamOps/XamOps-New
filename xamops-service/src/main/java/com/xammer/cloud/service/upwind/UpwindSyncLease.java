package com.xammer.cloud.service.upwind;

import com.xammer.cloud.repository.UpwindSyncLockRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Runs a sync only if this instance can claim the lease for it.
 *
 * Needed because live.xamops.com is an ASG: every instance runs the same image with the
 * scheduler enabled, so without this each cadence fires once per instance.
 *
 * Failing to acquire is the normal case on all but one instance, so it is logged at debug
 * and treated as success — not an error.
 */
@Component
@Slf4j
public class UpwindSyncLease {

    private final UpwindSyncLockRepository lockRepo;

    /** Stable per-process identity, so a lease can be traced to an instance. */
    private final String holder;

    public UpwindSyncLease(UpwindSyncLockRepository lockRepo) {
        this.lockRepo = lockRepo;
        this.holder = resolveHolder();
    }

    private static String resolveHolder() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = System.getenv().getOrDefault("HOSTNAME", "unknown-host");
        }
        return host + "/" + UUID.randomUUID().toString().substring(0, 8);
    }

    public String holder() {
        return holder;
    }

    /**
     * Executes {@code task} if the lease is free or expired, otherwise skips it.
     *
     * @param ttl how long the lease is held. Must exceed the task's worst-case duration,
     *            or another instance could start a second run while this one is still going.
     * @return true when the task ran here
     */
    public boolean runIfLeader(String name, Duration ttl, Runnable task) {
        Instant now = Instant.now();
        try {
            lockRepo.ensureRow(name, Instant.EPOCH);
            if (lockRepo.tryAcquire(name, holder, now, now.plus(ttl)) == 0) {
                log.debug("[Upwind] {} sync skipped — another instance holds the lease", name);
                return false;
            }
        } catch (Exception e) {
            // A lease failure must not silently stop syncing everywhere. Single-instance and
            // pre-migration deployments still need the sync to run, so fall through.
            log.warn("[Upwind] lease check failed for {} ({}) — running without it",
                    name, e.getMessage());
            task.run();
            return true;
        }

        try {
            task.run();
            return true;
        } finally {
            try {
                lockRepo.release(name, holder, Instant.now());
            } catch (Exception e) {
                // Harmless: the lease simply expires on its own.
                log.debug("[Upwind] lease release failed for {}: {}", name, e.getMessage());
            }
        }
    }

    /** Convenience for suppliers; mirrors {@link #runIfLeader} semantics. */
    public <T> T callIfLeader(String name, Duration ttl, Supplier<T> task, T skipped) {
        final Object[] box = new Object[1];
        boolean ran = runIfLeader(name, ttl, () -> box[0] = task.get());
        @SuppressWarnings("unchecked")
        T result = ran ? (T) box[0] : skipped;
        return result;
    }
}
