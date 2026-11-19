package com.xammer.cloud.service.autospotting.gcp;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * The GCP health thresholds of design-gcp.md §7.2, all under {@code autospotting.gcp.health.*} with the documented
 * defaults. The fallback daily cap, dwell, backoff and tail wait live in the shared
 * {@link com.xammer.cloud.service.autospotting.core.FallbackProperties} under {@code autospotting.gcp.fallback.*}.
 */
@Component
public class GcpHealthProperties {
    private final Duration shortfallWindow;
    private final Duration fastWindow;
    private final int fastPercent;
    private final int capacityErrors;
    private final int quotaErrors;
    private final Duration errorWindow;
    private final Duration exitQuiet;
    private final Duration stuckAge;
    private final Duration preemptionGap;

    public GcpHealthProperties(
            @Value("${autospotting.gcp.health.shortfall-window:15m}") Duration shortfallWindow,
            @Value("${autospotting.gcp.health.fast-window:10m}") Duration fastWindow,
            @Value("${autospotting.gcp.health.fast-percent:25}") int fastPercent,
            @Value("${autospotting.gcp.health.capacity-errors:3}") int capacityErrors,
            @Value("${autospotting.gcp.health.quota-errors:1}") int quotaErrors,
            @Value("${autospotting.gcp.health.error-window:15m}") Duration errorWindow,
            @Value("${autospotting.gcp.health.exit-quiet:30m}") Duration exitQuiet,
            @Value("${autospotting.gcp.health.stuck-age:10m}") Duration stuckAge,
            @Value("${autospotting.gcp.health.preemption-backfill-gap:6h}") Duration preemptionGap) {
        this.shortfallWindow = shortfallWindow;
        this.fastWindow = fastWindow;
        this.fastPercent = Math.max(1, fastPercent);
        this.capacityErrors = Math.max(1, capacityErrors);
        this.quotaErrors = Math.max(1, quotaErrors);
        this.errorWindow = errorWindow;
        this.exitQuiet = exitQuiet;
        this.stuckAge = stuckAge;
        this.preemptionGap = preemptionGap;
    }

    /** The documented defaults (tests and non-Spring callers). */
    public static GcpHealthProperties defaults() {
        return new GcpHealthProperties(Duration.ofMinutes(15), Duration.ofMinutes(10), 25, 3, 1, Duration.ofMinutes(15),
                Duration.ofMinutes(30), Duration.ofMinutes(10), Duration.ofHours(6));
    }

    /** Shortfall for this long, with a capacity or quota cause, enters FALLBACK (3 sweeps). */
    public Duration shortfallWindow() { return shortfallWindow; }
    /** A shortfall of {@link #fastPercent()} of the target for this long, with any capacity error, enters FALLBACK fast. */
    public Duration fastWindow() { return fastWindow; }
    public int fastPercent() { return fastPercent; }
    public int capacityErrors() { return capacityErrors; }
    public int quotaErrors() { return quotaErrors; }
    public Duration errorWindow() { return errorWindow; }
    /** {@code shortfall = 0} for this long, with the dwell elapsed, offers a FALLBACK exit. */
    public Duration exitQuiet() { return exitQuiet; }
    public Duration stuckAge() { return stuckAge; }
    /** A preemption cursor older than this is backfilled from audit logs instead of zone operations (design-gcp.md §8.2). */
    public Duration preemptionGap() { return preemptionGap; }
}
