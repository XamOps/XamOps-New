package com.xammer.cloud.service.upwind;

import com.xammer.cloud.repository.UpwindSyncLockRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
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
     * A task that can ask, mid-run, whether this instance still holds the lease.
     *
     * Given to tasks that do something destructive near the end of a long run — the sync's
     * mark-resolved pass — so they can stand down if the lease lapsed underneath them.
     */
    public interface LeasedTask {
        void run(BooleanSupplier stillLeader);
    }

    /**
     * Executes {@code task} if the lease is free or expired, otherwise skips it.
     *
     * @param ttl how long the lease is held. Should exceed the task's worst-case duration.
     *            If it does not, the task can still protect itself via the
     *            {@code stillLeader} check handed to {@link LeasedTask}.
     * @return true when the task ran here
     */
    public boolean runIfLeader(String name, Duration ttl, LeasedTask task) {
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
            //
            // stillLeader is TRUE here on purpose: with no lease table there is no second
            // instance to collide with, and returning false would permanently disable
            // reconciliation on those deployments.
            log.warn("[Upwind] lease check failed for {} ({}) — running without it",
                    name, e.getMessage());
            task.run(() -> true);
            return true;
        }

        // Heartbeat. Without it the TTL has to be guessed against the slowest possible sync,
        // and guessing low is not a benign failure: the full sync reconciles domain by domain
        // with API endpoints last, so once the lease lapses mid-run every later mark-resolved
        // pass stands down and stale rows are never cleaned. Measured on UAT: 15,325 endpoint
        // rows held for an account the API returns 4,919 for.
        //
        // Renewing keeps a live run's lease valid for as long as it is actually working, while
        // a crashed instance stops renewing and its lease still expires on schedule, so
        // takeover behaviour is unchanged.
        ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "upwind-lease-" + name);
            t.setDaemon(true);
            return t;
        });
        long periodSeconds = Math.max(30L, ttl.getSeconds() / 3);
        heartbeat.scheduleAtFixedRate(() -> {
            try {
                Instant beat = Instant.now();
                if (lockRepo.renew(name, holder, beat, beat.plus(ttl)) == 0) {
                    // Lost it: expired before this tick, or taken over. stillHeld() reports
                    // the same thing to the task, which is what makes it stand down.
                    log.warn("[Upwind] {} lease renewal found no lease held by {}", name, holder);
                }
            } catch (Exception e) {
                log.warn("[Upwind] {} lease renewal failed: {}", name, e.getMessage());
            }
        }, periodSeconds, periodSeconds, TimeUnit.SECONDS);

        try {
            task.run(() -> stillHeld(name));
            return true;
        } finally {
            heartbeat.shutdownNow();
            try {
                lockRepo.release(name, holder, Instant.now());
            } catch (Exception e) {
                // Harmless: the lease simply expires on its own.
                log.debug("[Upwind] lease release failed for {}: {}", name, e.getMessage());
            }
        }
    }

    /** Overload for tasks with nothing destructive to guard (e.g. the detection poll). */
    public boolean runIfLeader(String name, Duration ttl, Runnable task) {
        return runIfLeader(name, ttl, stillLeader -> task.run());
    }

    /**
     * True while this instance still owns an unexpired lease.
     *
     * Fails CLOSED (returns false) on error: the caller uses this to decide whether to
     * archive data, and skipping an archive pass merely leaves stale rows for the next run,
     * whereas archiving without the lease can delete another run's work.
     */
    public boolean stillHeld(String name) {
        try {
            return lockRepo.countHeldBy(name, holder, Instant.now()) > 0;
        } catch (Exception e) {
            log.warn("[Upwind] could not confirm {} lease ownership ({}) — assuming lost",
                    name, e.getMessage());
            return false;
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
