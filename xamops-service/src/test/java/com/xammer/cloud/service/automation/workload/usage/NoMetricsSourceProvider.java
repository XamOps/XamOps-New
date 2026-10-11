package com.xammer.cloud.service.automation.workload.usage;

import com.xammer.cloud.service.automation.workload.access.ClusterAccess;

import java.util.Map;

/**
 * A usage provider that never returns data, for the tests that assert the gated behaviour.
 *
 * <h2>Why this lives in test scope</h2>
 *
 * This carries exactly the behaviour the production {@code StubUsageHistoryProvider} used to have:
 * {@code NO_METRICS_SOURCE} always, never a number. That was the right <em>production</em> behaviour
 * while nothing could measure usage, and it is the wrong one now that
 * {@link PrometheusUsageHistoryProvider} can — a cluster with Prometheus installed must get real
 * recommendations. What is still worth keeping is the <em>property</em> it guarded: with no usage
 * source the read endpoint publishes an empty {@code workloads} array and a stated reason rather than
 * a fabricated recommendation. So the behaviour moves here, where it is a test input, and
 * {@code UsageContractTest} asserts that the only implementation on the production classpath is the
 * real one.
 *
 * <p>{@code ProductionClassScan} filters by code source, so this class being on the test classpath
 * does not make that assertion fail.
 */
public final class NoMetricsSourceProvider implements UsageHistoryProvider {

    @Override
    public String sourceName() {
        return "none";
    }

    @Override
    public boolean reachable(ClusterAccess access) {
        return false;
    }

    @Override
    public Map<String, ContainerUsageAggregate> aggregates(ClusterAccess access, UsageQuery query)
            throws UsageUnavailableException {
        throw new UsageUnavailableException(UsageUnavailableException.Kind.NO_METRICS_SOURCE,
                "No usage source is configured for this cluster, so XamOps cannot compute"
                        + " recommendations. Percentile history has to be computed inside the cluster:"
                        + " install Prometheus, or the XamOps agent, and the engine will use it.");
    }
}
