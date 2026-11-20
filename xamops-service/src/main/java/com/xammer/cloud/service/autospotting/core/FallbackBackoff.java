package com.xammer.cloud.service.autospotting.core;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * When the next FALLBACK_EXIT may be tried (design-aws.md §6.5). The first attempt waits the minimum dwell
 * (60 min) from the FALLBACK transition. After a failed attempt the waits are 1 h, 2 h, 4 h, then 8 h each
 * time, counted from the end of the failed attempt. A group that re-entered FALLBACK within 30 min of an exit
 * doubles the interval. Pure: no I/O, time is a parameter.
 */
public final class FallbackBackoff {
    private final Duration dwell;
    private final List<Duration> series;

    public FallbackBackoff(Duration dwell, List<Duration> series) {
        this.dwell = dwell;
        this.series = series;
    }

    public static FallbackBackoff of(FallbackProperties p) {
        return new FallbackBackoff(p.dwell(), p.backoff());
    }

    /**
     * @param fallbackSince      when the group entered FALLBACK
     * @param lastExitFinishedAt when the last failed exit ended, or null
     * @param failedExits        failed exits since the group entered FALLBACK
     * @param fastReentry        true when this FALLBACK began within 30 min of an exit
     */
    public Instant nextAttemptAt(Instant fallbackSince, Instant lastExitFinishedAt, int failedExits,
                                 boolean fastReentry) {
        Duration wait = failedExits <= 0 ? dwell : series.get(Math.min(failedExits - 1, series.size() - 1));
        if (fastReentry) {
            wait = wait.multipliedBy(2);
        }
        Instant anchor = failedExits <= 0 || lastExitFinishedAt == null ? fallbackSince : lastExitFinishedAt;
        return anchor.plus(wait);
    }
}
