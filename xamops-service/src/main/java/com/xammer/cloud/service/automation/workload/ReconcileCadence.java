package com.xammer.cloud.service.automation.workload;

import java.time.Duration;

/**
 * When to look at a workload again. A pure function, so the deferred scheduler consumes it and
 * nothing else decides timing.
 *
 * <p>The intervals are the Go reference's requeue cadence, kept because they were the one part of
 * that loop the harness did not find fault with. The <em>scheduler</em> is deferred; separating the
 * two means the cadence is unit-testable now and the scheduler has no numbers of its own later.
 */
public final class ReconcileCadence {

    private static final Duration NORMAL = Duration.ofMinutes(30);
    private static final Duration VOLATILE = Duration.ofMinutes(2);
    private static final Duration METRICS_FAILURE = Duration.ofMinutes(1);
    private static final Duration DISABLED = Duration.ofHours(1);

    private ReconcileCadence() {
    }

    public static Duration next(ReconcileOutcome outcome) {
        if (outcome == null) {
            throw new IllegalArgumentException("outcome is mandatory");
        }
        return switch (outcome) {
            // Long enough that a recommendation is never recomputed against the churn its own
            // predecessor caused; short enough to notice a real load change within an hour.
            case NORMAL -> NORMAL;
            // Come back soon: the state is transient by definition.
            case VOLATILE -> VOLATILE;
            // Retry quickly, because the usual cause is a brief scrape failure.
            case METRICS_FAILURE -> METRICS_FAILURE;
            // Nothing to do; just notice eventually if the policy changes.
            case DISABLED -> DISABLED;
        };
    }
}
