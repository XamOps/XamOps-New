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
 * <h2>What the HPA decisions need (D1–D4)</h2>
 *
 * Beyond the coexistence freeze, the horizontal decisions read a handful of status facts. Each one
 * states how an unreadable value is handled, so a decision can <em>refuse</em> rather than guess:
 * {@code historyReadable} false makes D1/D2 decline to move the ceiling at all
 * ({@link HpaReason#BELOW_OBSERVED_NEED}), and a null {@code gitopsManaged} means ownership was not
 * yet detected.
 *
 * @param name                        the HPA's name
 * @param namespace                   the HPA's namespace; must equal the workload's
 * @param scaleTargetKind             {@code scaleTargetRef.kind}
 * @param scaleTargetName             {@code scaleTargetRef.name}
 * @param minReplicas                 as configured
 * @param maxReplicas                 as configured
 * @param currentReplicas             as observed
 * @param cpuResourceMetricPresent    a {@code Resource: cpu} metric exists ({@code Utilization} or
 *                                    {@code AverageValue}) — either way the CPU request is the
 *                                    denominator
 * @param memResourceMetricPresent    a {@code Resource: memory} metric exists
 * @param targetCpuUtilizationPct     the configured CPU target, or null
 * @param lastScaleTime               {@code status.lastScaleTime}, or null if it has never scaled
 * @param gitopsManaged               Argo CD / Flux / Helm owns the HPA; null when not yet detected
 * @param scalingLimited              {@code ScalingLimited=True} with {@code TooManyReplicas}
 * @param atLimitSince                when the HPA first dwelt at its ceiling, or null if it is not
 * @param currentMetricPct            observed CPU utilisation from {@code status.currentMetrics}, or
 *                                    null when unreadable
 * @param observedPeakDesiredReplicas peak {@code desiredReplicas} over the look-back window, or null
 *                                    when the history was unreadable
 * @param replicaChangeCount          replica changes over the look-back window, or null when the
 *                                    history was unreadable
 * @param userMaxReplicasCeiling      an operator-set hard ceiling D1 may never exceed, or null
 * @param historyReadable             false when kube-state-metrics history could not be read; a
 *                                    decision that needs it then refuses rather than guesses
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
                      Instant lastScaleTime,
                      Boolean gitopsManaged,
                      boolean scalingLimited,
                      Instant atLimitSince,
                      Integer currentMetricPct,
                      Integer observedPeakDesiredReplicas,
                      Integer replicaChangeCount,
                      Integer userMaxReplicasCeiling,
                      boolean historyReadable) {

    /**
     * The historical shape: the eleven fields the vertical spine shipped with, before the HPA
     * decisions were added. New fields default to "not detected / not limited / history readable",
     * so a reader that only knows about the coexistence freeze keeps working unchanged.
     */
    public HpaView(String name, String namespace, String scaleTargetKind, String scaleTargetName,
                   Integer minReplicas, Integer maxReplicas, Integer currentReplicas,
                   boolean cpuResourceMetricPresent, boolean memResourceMetricPresent,
                   Integer targetCpuUtilizationPct, Instant lastScaleTime) {
        this(name, namespace, scaleTargetKind, scaleTargetName, minReplicas, maxReplicas,
                currentReplicas, cpuResourceMetricPresent, memResourceMetricPresent,
                targetCpuUtilizationPct, lastScaleTime,
                null, false, null, null, null, null, null, true);
    }

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

    /** True when the HPA has dwelt at its ceiling for at least {@code dwell}. */
    public boolean dweltAtCeiling(java.time.Duration dwell, Instant now) {
        return scalingLimited && atLimitSince != null
                && !atLimitSince.isAfter(now.minus(dwell));
    }

    /** Whether this HPA is owned by a GitOps controller (never null-safe false unless detected). */
    public boolean isGitopsManaged() {
        return Boolean.TRUE.equals(gitopsManaged);
    }

    /** A CPU HPA on a Deployment in the same namespace, the common case, for fixtures. */
    public static HpaView cpuHpa(String name, WorkloadRef ref, int targetUtilizationPct,
                                 int minReplicas, int maxReplicas) {
        return new HpaView(name, ref.namespace(), ref.kind(), ref.name(), minReplicas, maxReplicas,
                null, true, false, targetUtilizationPct, null);
    }

    /** This HPA with the D1 ceiling-dwell inputs set, for fixtures and read-path assembly. */
    public HpaView withScalingLimited(boolean limited, Instant since, Integer currentMetricPct) {
        return new HpaView(name, namespace, scaleTargetKind, scaleTargetName, minReplicas, maxReplicas,
                currentReplicas, cpuResourceMetricPresent, memResourceMetricPresent,
                targetCpuUtilizationPct, lastScaleTime, gitopsManaged, limited, since, currentMetricPct,
                observedPeakDesiredReplicas, replicaChangeCount, userMaxReplicasCeiling, historyReadable);
    }

    /** This HPA with the current replica count set, for fixtures. */
    public HpaView withCurrentReplicas(Integer current) {
        return new HpaView(name, namespace, scaleTargetKind, scaleTargetName, minReplicas, maxReplicas,
                current, cpuResourceMetricPresent, memResourceMetricPresent,
                targetCpuUtilizationPct, lastScaleTime, gitopsManaged, scalingLimited, atLimitSince,
                currentMetricPct, observedPeakDesiredReplicas, replicaChangeCount,
                userMaxReplicasCeiling, historyReadable);
    }

    /** This HPA with the look-back history set, for fixtures and read-path assembly. */
    public HpaView withHistory(Integer observedPeak, Integer replicaChanges, boolean readable) {
        return new HpaView(name, namespace, scaleTargetKind, scaleTargetName, minReplicas, maxReplicas,
                currentReplicas, cpuResourceMetricPresent, memResourceMetricPresent,
                targetCpuUtilizationPct, lastScaleTime, gitopsManaged, scalingLimited, atLimitSince,
                currentMetricPct, observedPeak, replicaChanges, userMaxReplicasCeiling, readable);
    }

    /** This HPA with the operator-set hard ceiling, for fixtures and read-path assembly. */
    public HpaView withUserMaxReplicasCeiling(Integer ceiling) {
        return new HpaView(name, namespace, scaleTargetKind, scaleTargetName, minReplicas, maxReplicas,
                currentReplicas, cpuResourceMetricPresent, memResourceMetricPresent,
                targetCpuUtilizationPct, lastScaleTime, gitopsManaged, scalingLimited, atLimitSince,
                currentMetricPct, observedPeakDesiredReplicas, replicaChangeCount, ceiling, historyReadable);
    }

    /** This HPA flagged as GitOps-owned, for fixtures and read-path assembly. */
    public HpaView withGitopsManaged(Boolean managed) {
        return new HpaView(name, namespace, scaleTargetKind, scaleTargetName, minReplicas, maxReplicas,
                currentReplicas, cpuResourceMetricPresent, memResourceMetricPresent,
                targetCpuUtilizationPct, lastScaleTime, managed, scalingLimited, atLimitSince,
                currentMetricPct, observedPeakDesiredReplicas, replicaChangeCount,
                userMaxReplicasCeiling, historyReadable);
    }
}
