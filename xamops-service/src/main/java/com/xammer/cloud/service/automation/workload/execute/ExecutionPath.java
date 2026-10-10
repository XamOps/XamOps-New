package com.xammer.cloud.service.automation.workload.execute;

/**
 * How a workload action reaches the cluster.
 *
 * <p>The ordering matters: {@link #AGENT} is the primary path and {@link #DIRECT} an optional fast
 * path, which is the opposite of the intuitive reading. Verified reasons:
 *
 * <ul>
 *   <li>The agent polls {@code GET /api/v1/agent/commands} <strong>outbound-only</strong>, so it
 *       already reaches private-endpoint clusters with no inbound network path.</li>
 *   <li>The agent skips any command type it does not recognise, so adding a workload-autoscaling
 *       command type is forward-compatible with agents already deployed.</li>
 *   <li>The agent already collects per-pod, per-container usage from {@code metrics.k8s.io}, and the
 *       backend retains <strong>no</strong> usage history. Percentiles therefore have to be computed
 *       inside the cluster regardless of which path applies the change — "agentless vertical
 *       rightsizing" removes the need for an in-cluster <em>actuator</em>, never for an in-cluster
 *       <em>observer</em>.</li>
 * </ul>
 */
public enum ExecutionPath {

    /** Primary: enqueue a command the in-cluster agent fetches, applies and reports on later. */
    AGENT,

    /** Optional fast path: the backend patches the API server itself, synchronously reachable. */
    DIRECT
}
