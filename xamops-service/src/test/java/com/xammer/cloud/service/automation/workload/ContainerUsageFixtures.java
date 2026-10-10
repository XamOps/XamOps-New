package com.xammer.cloud.service.automation.workload;

import com.xammer.cloud.service.automation.workload.usage.ContainerUsageAggregate;
import com.xammer.cloud.service.automation.workload.usage.PercentileSet;

import java.time.Duration;
import java.time.Instant;

/**
 * Usage aggregates for tests, shared by the engine suite and the API mapping suite.
 *
 * <p>Public because the API tests live in a different package. A fixed {@link #NOW} keeps every
 * assertion deterministic: the engine's only ambient input is its {@link java.time.Clock}, so with
 * both pinned the whole pipeline is reproducible.
 */
public final class ContainerUsageFixtures {

    public static final Instant NOW = Instant.parse("2026-10-10T06:00:00Z");
    public static final Instant WINDOW_START = NOW.minus(Duration.ofHours(24));

    private ContainerUsageFixtures() {
    }

    /** Full coverage over the full 24h window: the normal case. */
    public static ContainerUsageAggregate usage(String container, long cpuMillisP95, long memBytesP95) {
        return usage(container, cpuMillisP95, memBytesP95, 0.0, 0, 1.0, "test");
    }

    public static ContainerUsageAggregate usage(String container, long cpuMillisP95, long memBytesP95,
                                                double throttleRatio, int oomKills, double coverage,
                                                String sourceName) {
        return new ContainerUsageAggregate(container,
                PercentileSet.single(95, cpuMillisP95),
                PercentileSet.single(95, memBytesP95),
                throttleRatio, oomKills, WINDOW_START, NOW, coverage, sourceName);
    }
}
