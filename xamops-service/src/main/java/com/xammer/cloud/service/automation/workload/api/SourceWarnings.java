package com.xammer.cloud.service.automation.workload.api;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * Rate-limits WARN logging for source failures to one per (cluster, source) per
 * {@link #INTERVAL} (design v2 §9.0). A broken Prometheus stays visible in the log without one line
 * per chart per page view; the repeats are logged at DEBUG.
 *
 * <p>The key space is bounded by registered clusters times a fixed set of source names.
 */
final class SourceWarnings {

    static final Duration INTERVAL = Duration.ofMinutes(10);

    private final Map<String, Long> lastWarnedAtNanos = new ConcurrentHashMap<>();
    private final LongSupplier nanoClock;

    SourceWarnings(LongSupplier nanoClock) {
        this.nanoClock = nanoClock;
    }

    /** Whether this (cluster, source) may WARN now; claiming the slot when it may. */
    boolean shouldWarn(Long clusterConfigId, String source) {
        long now = nanoClock.getAsLong();
        long interval = INTERVAL.toNanos();
        AtomicBoolean warn = new AtomicBoolean(false);
        lastWarnedAtNanos.compute(clusterConfigId + ":" + source, (key, last) -> {
            if (last == null || now - last >= interval) {
                warn.set(true);
                return now;
            }
            return last;
        });
        return warn.get();
    }
}
