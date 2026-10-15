package com.xammer.cloud.service.automation.workload;

import java.util.List;

/**
 * Everything the engine is allowed to know about one workload.
 *
 * <p>These are the engine's <em>own</em> types, not fabric8 types. fabric8 appears only at provider
 * boundaries. That keeps the engine free of {@code Quantity} strings (so a rounding
 * {@code Value()}-equivalent cannot be written, audit bug 3) and makes the nine measured reference
 * workloads assertable as literal fixtures in millisecond unit tests with no cluster.
 *
 * @param ref                  identity, namespace mandatory
 * @param replicas             {@code spec.replicas}
 * @param rolloutInProgress    {@code metadata.generation != status.observedGeneration}
 * @param lockAnnotationActive {@code xamops.io/lock: active} is present on the workload
 * @param hpa                  the HPA targeting this workload, or null
 * @param pdbMinAvailable      a PDB's {@code minAvailable}, or null — carried for the deferred
 *                             horizontal half, which must not propose {@code minReplicas} below it
 * @param gitopsManaged        whether Argo CD or Flux owns the template; null when not yet detected
 *                             (detection is deferred, so it is null in this run)
 * @param nativeVpaMode        how a native VPA coexists with this workload — the input to the
 *                             symmetric VPA rule. Never null; {@link NativeVpaMode#NONE} when no VPA
 *                             targets it. Sourced from {@code UpstreamVpaReader}.
 * @param containers           every container, sidecars included and distinct
 */
public record WorkloadSnapshot(WorkloadRef ref,
                               int replicas,
                               boolean rolloutInProgress,
                               boolean lockAnnotationActive,
                               HpaView hpa,
                               Integer pdbMinAvailable,
                               Boolean gitopsManaged,
                               NativeVpaMode nativeVpaMode,
                               List<ContainerSnapshot> containers) {

    /**
     * The eight-field shape the vertical spine shipped with, before native-VPA coexistence became
     * symmetric. {@code nativeVpaMode} defaults to {@link NativeVpaMode#NONE}, so a caller that does
     * not read VPA ownership behaves exactly as before.
     */
    public WorkloadSnapshot(WorkloadRef ref, int replicas, boolean rolloutInProgress,
                            boolean lockAnnotationActive, HpaView hpa, Integer pdbMinAvailable,
                            Boolean gitopsManaged, List<ContainerSnapshot> containers) {
        this(ref, replicas, rolloutInProgress, lockAnnotationActive, hpa, pdbMinAvailable,
                gitopsManaged, NativeVpaMode.NONE, containers);
    }

    public WorkloadSnapshot {
        if (ref == null) {
            throw new IllegalArgumentException("ref is mandatory");
        }
        nativeVpaMode = nativeVpaMode == null ? NativeVpaMode.NONE : nativeVpaMode;
        if (replicas < 0) {
            throw new IllegalArgumentException("replicas cannot be negative");
        }
        if (containers == null || containers.isEmpty()) {
            throw new IllegalArgumentException("a workload with no containers is not a workload");
        }
        containers = List.copyOf(containers);
        long distinct = containers.stream().map(ContainerSnapshot::name).distinct().count();
        if (distinct != containers.size()) {
            throw new IllegalArgumentException("duplicate container names in one workload");
        }
    }

    /** The HPA only if it genuinely targets this workload: same namespace, same kind, same name. */
    public HpaView effectiveHpa() {
        return hpa != null && hpa.targets(ref) ? hpa : null;
    }

    /** Whether a native VPA ownership state makes the vertical half refuse this workload. */
    public boolean nativeVpaRefusesVertical() {
        return nativeVpaMode != null && nativeVpaMode.refusesVertical();
    }

    public List<ResourceEnvelope> currentEnvelopes() {
        return containers.stream().map(ContainerSnapshot::current).toList();
    }

    public QosClass qosClass() {
        return QosRules.podClass(currentEnvelopes());
    }
}
