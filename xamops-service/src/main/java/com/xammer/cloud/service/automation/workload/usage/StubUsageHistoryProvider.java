package com.xammer.cloud.service.automation.workload.usage;

import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * The only usage provider wired in this run. It never returns data.
 *
 * <p>This is deliberate, and it is the mechanism that keeps the read endpoint honest. With no real
 * provider, the endpoint reports {@code NO_METRICS_SOURCE} and an <strong>empty</strong>
 * {@code workloads} array. It cannot emit a zero, a placeholder or an unevaluated workload, because
 * there is no code path that produces one — the measured {@code 1Mi} fabricated baseline for
 * {@code no-requests-sidecar-app} is exactly the failure being designed out.
 *
 * <p>A stub that returned plausible-looking numbers would be worse than no feature at all: the
 * numbers would be indistinguishable from real ones in the UI, and somebody would act on them.
 */
@Service
public class StubUsageHistoryProvider implements UsageHistoryProvider {

    @Override
    public String sourceName() {
        return "none";
    }

    @Override
    public Map<String, ContainerUsageAggregate> aggregates(UsageQuery query) throws UsageUnavailableException {
        throw new UsageUnavailableException(UsageUnavailableException.Kind.NO_METRICS_SOURCE,
                "No usage source is configured for this cluster, so XamOps cannot compute"
                        + " recommendations. Percentile history has to be computed inside the cluster:"
                        + " install Prometheus, or the XamOps agent, and the engine will use it.");
    }
}
