package com.xammer.cloud.service.automation.workload;

/**
 * Why an RPS-based replica sizing has — or has not — a number (F3).
 *
 * <p>A deliberately small, <strong>separate</strong> enum from {@link HpaReason}: the HPA reason
 * vocabulary and its ordering are pinned by {@code WorkloadReasonCodeContractTest}, and the RPS
 * advisory rides its own {@link RpsReplicaRecommendation} rather than an {@link HpaDecision}, so it
 * never needs to borrow an HpaReason. Adding one here cannot move that contract.
 *
 * <p>The three states are the only honest outcomes of §3.2's model:
 *
 * <ul>
 *   <li>{@link #RPS_SIZED} — observed throughput and a real per-replica capacity were both present,
 *       so a recommended replica count was computed (bounded by the HPA range when it exists,
 *       unbounded with a {@code (no HPA range)} note otherwise).</li>
 *   <li>{@link #NO_RPS_SOURCE} — the feature is off, or no request-rate metric is configured, or the
 *       configured metric returned nothing / {@code NaN}. The throughput is unknown, so no number is
 *       emitted — a dash with this reason, never a guess.</li>
 *   <li>{@link #NO_CAPACITY_BASIS} — throughput is known but no real per-replica capacity is
 *       configured. Capacity is never self-derived from the observed RPS (that cancellation was the
 *       H1 bug), so without a configured capacity there is nothing honest to divide by.</li>
 * </ul>
 */
public enum RpsReason {
    RPS_SIZED,
    NO_RPS_SOURCE,
    NO_CAPACITY_BASIS
}
