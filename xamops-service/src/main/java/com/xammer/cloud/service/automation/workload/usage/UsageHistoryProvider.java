package com.xammer.cloud.service.automation.workload.usage;

import com.xammer.cloud.service.automation.workload.access.ClusterAccess;

import java.util.Map;
import java.util.OptionalDouble;

/**
 * Supplies pre-aggregated per-container usage percentiles over a window.
 *
 * <p>The implementation is {@link PrometheusUsageHistoryProvider}, which queries the customer
 * cluster's own Prometheus through the Kubernetes API service proxy. It raises a typed
 * {@code NO_METRICS_SOURCE} / {@code INSUFFICIENT_HISTORY} rather than returning a guess, which is
 * what keeps the read endpoint honest.
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
 * <h2>Why both methods take a {@link ClusterAccess}</h2>
 *
 * Usage lives inside the customer's cluster, so measuring it needs the connection the caller already
 * opened. Passing it in — rather than having the provider open its own — means one cluster
 * connection serves the whole request: the inventory read, every workload's usage, and the
 * reachability probe. The parameter is {@code ClusterAccess}, this codebase's own type, so the
 * interface stays free of fabric8 even though an implementation of it will not be.
 *
 * <h2>Deferred implementation</h2>
 *
 * <strong>Agent-side rolling window:</strong> the agent already collects per-pod, per-container
 * {@code PodMetrics} from {@code metrics.k8s.io} across all namespaces, and would maintain the window
 * in memory the way upstream VPA does with decaying histograms. Needs no Prometheus at all, and is
 * the path for a private-endpoint cluster the backend cannot dial.
 */
public interface UsageHistoryProvider {

    /** Stable name, reported as the recommendation's basis. */
    String sourceName();

    /**
     * Whether this source can answer for this cluster <strong>right now</strong>.
     *
     * <p>A real probe, not a property read: the read endpoint publishes the answer as
     * {@code metricsSource.reachable} and gates on it, and "a provider bean exists" is not evidence
     * that a customer's cluster has Prometheus installed.
     */
    boolean reachable(ClusterAccess access);

    /**
     * @return one aggregate per requested container name; never one value reused across containers
     * @throws UsageUnavailableException {@code NO_METRICS_SOURCE} or {@code INSUFFICIENT_HISTORY},
     *                                   never anything else
     */
    Map<String, ContainerUsageAggregate> aggregates(ClusterAccess access, UsageQuery query)
            throws UsageUnavailableException;

    /**
     * The observed request rate for one workload, summed across its containers, for the RPS-based
     * replica sizing (F3). <strong>Best-effort and honest about absence.</strong>
     *
     * <p>Unlike {@link #aggregates}, this never throws a typed unavailable: a missing RPS source is
     * not an error, it is simply "no number", which the caller maps to the honest
     * {@code NO_RPS_SOURCE} dash. The method returns {@link OptionalDouble#empty()} for every absence
     * case:
     *
     * <ul>
     *   <li>a blank {@code query.metricExpr()} (no request-rate metric configured);</li>
     *   <li>no cluster connection ({@code access == null});</li>
     *   <li>the transport reporting {@code NO_METRICS_SOURCE} / refusing the query;</li>
     *   <li>an empty result vector (no finite series matched the selector);</li>
     *   <li>a {@code NaN} / non-finite value.</li>
     * </ul>
     *
     * <p>When a request-rate series is present it renders {@link PromQl#requestRate} over the same
     * namespace + owner-resolved pods + container selector the other queries use, then
     * <strong>sums</strong> the per-container series into one scalar (the total throughput the
     * replica count must serve). It never returns a guessed rate, and it never derives a per-replica
     * capacity from this value.
     *
     * @return the summed request rate, or empty on any absence case above
     */
    OptionalDouble observedRps(ClusterAccess access, RpsQuery query);
}
