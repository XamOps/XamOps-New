package com.xammer.cloud.service.automation.workload;

import java.time.Instant;

/**
 * The HPA that targets a workload, as the engine needs to see it.
 *
 * <h2>Why this is here at all (audit bug 5)</h2>
 *
 * <strong>A CPU request is the HPA's utilisation denominator.</strong> Changing it silently moves the
 * HPA's set point. The measured failure: in one cycle, for {@code team-b/php-apache}, the Go
 * rightsizer said cut the CPU request 200m → 144m while the Go HPA manager said raise the target
 * 50% → 80% and max 5 → 10 — three numbers for one workload, from one code path, with no
 * cross-reference. Cutting 200m → 144m alone would have moved measured utilisation from ~65% to ~90%
 * at unchanged load.
 *
 * <p>So the engine reads the HPA and freezes the resource the HPA manages. The match is on
 * {@code scaleTargetRef} kind + name <strong>in the same namespace</strong>, never on a name prefix,
 * which is what {@link #targets(WorkloadRef)} exists to enforce.
 *
 * @param name                     the HPA's name
 * @param namespace                the HPA's namespace; must equal the workload's
 * @param scaleTargetKind          {@code scaleTargetRef.kind}
 * @param scaleTargetName          {@code scaleTargetRef.name}
 * @param minReplicas              as configured
 * @param maxReplicas              as configured
 * @param currentReplicas          as observed
 * @param cpuResourceMetricPresent a {@code Resource: cpu} metric exists ({@code Utilization} or
 *                                 {@code AverageValue}) — either way the CPU request is the
 *                                 denominator
 * @param memResourceMetricPresent a {@code Resource: memory} metric exists
 * @param targetCpuUtilizationPct  the configured CPU target, or null
 * @param lastScaleTime            {@code status.lastScaleTime}, or null if it has never scaled
 */
public record HpaView(String name,
                      String namespace,
                      String scaleTargetKind,
                      String scaleTargetName,
                      Integer minReplicas,
                      Integer maxReplicas,
                      Integer currentReplicas,
                      boolean cpuResourceMetricPresent,
                      boolean memResourceMetricPresent,
                      Integer targetCpuUtilizationPct,
                      Instant lastScaleTime) {

    /**
     * Whether this HPA really targets {@code ref}.
     *
     * <p>Namespace, kind and name must all match exactly. A prefix match would make
     * {@code php-apache} and {@code php-apache-canary} the same workload.
     */
    public boolean targets(WorkloadRef ref) {
        return ref != null
                && ref.namespace().equals(namespace)
                && ref.kind().equalsIgnoreCase(scaleTargetKind)
                && ref.name().equals(scaleTargetName);
    }

    /** A CPU-metric HPA that scaled within {@code window} makes current usage a transient. */
    public boolean scaledWithin(java.time.Duration window, Instant now) {
        return lastScaleTime != null && lastScaleTime.isAfter(now.minus(window));
    }

    /** A CPU HPA on a Deployment in the same namespace, the common case, for fixtures. */
    public static HpaView cpuHpa(String name, WorkloadRef ref, int targetUtilizationPct,
                                 int minReplicas, int maxReplicas) {
        return new HpaView(name, ref.namespace(), ref.kind(), ref.name(), minReplicas, maxReplicas,
                null, true, false, targetUtilizationPct, null);
    }
}
