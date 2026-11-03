package com.xammer.cloud.service.autospotting.core.lease;

import java.time.Duration;

/**
 * A held lease, group or job (design-core.md §3.3, §3.4). Holds the per-acquisition
 * {@code <instanceId>:<component>:<uuid>} token and the generation the acquire returned. The handle
 * is the fencing token every write and every cloud call checks:
 *
 * <ul>
 *   <li>{@link #generation()} is passed to the fenced write ({@link FencedTx}).</li>
 *   <li>{@link #assertHeld(Duration)} is called before every mutating cloud call (§3.4): it checks
 *       the handle isn't {@link #isLost() LOST} and that the time left on the lease, measured with
 *       the local monotonic clock from the last successful renew minus a 10 s skew margin, exceeds
 *       the call's timeout; if short it renews synchronously first, and if the renew fails it throws
 *       so the step makes no cloud call.</li>
 * </ul>
 *
 * <p>The monotonic clock ({@link System#nanoTime()}) is deliberate: it is immune to wall-clock
 * jumps, and the server-side {@code clock_timestamp()} comparison in the DB statements is the
 * authority for who actually holds the lease. The handle is created, renewed and marked lost by
 * {@link LeaseService}; a renewal that updates zero rows marks it LOST.
 *
 * <p>Not thread-safe across callers beyond the single state fields it mutates; a handle is driven by
 * one owning component (its {@code component} token) plus the heartbeat executor, which only renews.
 */
public final class LeaseHandle {

    /** Which store the handle is against: the group row or the job-lease row. */
    public enum Kind { GROUP, JOB }

    private final Kind kind;
    private final String resourceId;   // group UUID as text, or job name
    private final String token;
    private final long generation;
    private final Duration ttl;
    private final Duration heartbeat;
    private final Duration skewMargin;
    private final Renewer renewer;

    private volatile boolean lost;
    /** {@link System#nanoTime()} of the last successful acquire or renew. */
    private volatile long lastRenewNanos;

    /** How the handle renews itself (implemented by {@link LeaseService}). */
    @FunctionalInterface
    public interface Renewer {
        /** @return true when one row was renewed (still held); false means the lease is lost. */
        boolean renew(LeaseHandle handle);
    }

    public LeaseHandle(Kind kind, String resourceId, String token, long generation, Duration ttl,
                       Duration heartbeat, Duration skewMargin, Renewer renewer) {
        this.kind = kind;
        this.resourceId = resourceId;
        this.token = token;
        this.generation = generation;
        this.ttl = ttl;
        this.heartbeat = heartbeat;
        this.skewMargin = skewMargin;
        this.renewer = renewer;
        this.lastRenewNanos = System.nanoTime();
    }

    public Kind kind() {
        return kind;
    }

    public String resourceId() {
        return resourceId;
    }

    public String token() {
        return token;
    }

    public long generation() {
        return generation;
    }

    public Duration ttl() {
        return ttl;
    }

    public Duration heartbeat() {
        return heartbeat;
    }

    public boolean isLost() {
        return lost;
    }

    /** Local monotonic time left on the lease: ttl minus the elapsed time since the last renew. */
    public Duration timeLeft() {
        long elapsedNanos = System.nanoTime() - lastRenewNanos;
        return ttl.minusNanos(elapsedNanos);
    }

    /**
     * Before every mutating cloud call (§3.4). Throws {@link LeaseLostException} when the handle is
     * lost or a needed renew fails; otherwise returns with the lease safely held for at least
     * {@code callTimeout}. Renews synchronously when the time left minus the skew margin no longer
     * covers the call.
     */
    public void assertHeld(Duration callTimeout) {
        if (lost) {
            throw new LeaseLostException("lease " + describe() + " already lost");
        }
        if (timeLeft().minus(skewMargin).compareTo(callTimeout) <= 0) {
            boolean renewed = renewer.renew(this);
            if (!renewed) {
                throw new LeaseLostException("lease " + describe() + " could not be renewed before a cloud call");
            }
        }
        if (lost) {
            throw new LeaseLostException("lease " + describe() + " lost during renew");
        }
    }

    /** Called by {@link LeaseService} after a successful renew. */
    public void markRenewed() {
        this.lastRenewNanos = System.nanoTime();
    }

    /**
     * Marks the handle lost: the holder must stop using it. Called by {@link LeaseService} when a
     * renew updates zero rows or on release. Marking lost is always fail-safe (the holder stops), so
     * it is safe to call directly.
     */
    public void markLost() {
        this.lost = true;
    }

    private String describe() {
        return kind + ":" + resourceId + " (token=" + token + ", gen=" + generation + ")";
    }

    @Override
    public String toString() {
        return "LeaseHandle{" + describe() + ", lost=" + lost + "}";
    }
}
