package com.xammer.cloud.service.automation.workload.usage;

import java.util.Map;

/**
 * Supplies pre-aggregated per-container usage percentiles over a window.
 *
 * <p><strong>Defined here; no real implementation exists in this run</strong> — only
 * {@link StubUsageHistoryProvider}, which always reports {@code NO_METRICS_SOURCE}. That is what
 * makes the read endpoint honest rather than fabricated.
 *
 * <h2>Why the contract is "pre-aggregated", not "raw time series"</h2>
 *
 * Percentile computation is <em>measurement</em>, not decision, and it has to happen where the
 * history lives. The backend retains none: {@code agent_data_cache} is unique on
 * {@code (cluster_config_id, data_type)} with a single {@code collected_at}, so every push
 * overwrites the last, and a backend time-series store is a separate roadmap item of XL effort. An
 * interface shaped around raw series would therefore be unimplementable today. Aggregating
 * observations is not deciding — the engine still owns every decision: floors, hysteresis, QoS,
 * HPA coexistence and findings.
 *
 * <h2>Requirements on any implementation</h2>
 *
 * <ul>
 *   <li><strong>Per container.</strong> One aggregate per container name, sidecars distinct. Never
 *       one value reused across containers (audit bug 8).</li>
 *   <li><strong>Namespace-scoped and owner-selected.</strong> Resolve pods through owner references,
 *       never by a {@code pod=~"name-.*"} regex.</li>
 *   <li><strong>Exactly the requested percentile.</strong> The returned
 *       {@link PercentileSet#at(int)} must answer {@link UsageQuery#percentile()} or throw. No
 *       neighbour substitution.</li>
 *   <li><strong>Mandatory coverage and window bounds</strong>, so {@code INSUFFICIENT_HISTORY} is
 *       computed rather than guessed.</li>
 * </ul>
 *
 * <h2>Deferred implementations</h2>
 *
 * <ol>
 *   <li><strong>Agent-side rolling window</strong> (primary): the agent already collects per-pod,
 *       per-container {@code PodMetrics} from {@code metrics.k8s.io} across all namespaces, and would
 *       maintain the window in memory the way upstream VPA does with decaying histograms. Needs no
 *       Prometheus at all.</li>
 *   <li><strong>PromQL</strong>: {@code quantile_over_time} over
 *       {@code container_cpu_usage_seconds_total} and {@code container_memory_working_set_bytes},
 *       plus the CFS throttling ratio, reached through the Kubernetes API service proxy restricted
 *       by {@code resourceNames} to the Prometheus service. Two deliberate departures from the Go
 *       queries: <strong>p95, not p80</strong> for CPU, and a memory <em>percentile</em> rather than
 *       {@code max_over_time}, which overshoots.</li>
 * </ol>
 */
public interface UsageHistoryProvider {

    /** Stable name, reported as the recommendation's basis. {@code "none"} for the stub. */
    String sourceName();

    /**
     * @return one aggregate per requested container name; never one value reused across containers
     * @throws UsageUnavailableException {@code NO_METRICS_SOURCE} or {@code INSUFFICIENT_HISTORY},
     *                                   never anything else
     */
    Map<String, ContainerUsageAggregate> aggregates(UsageQuery query) throws UsageUnavailableException;
}
