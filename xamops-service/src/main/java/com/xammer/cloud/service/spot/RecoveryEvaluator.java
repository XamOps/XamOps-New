package com.xammer.cloud.service.spot;

import com.xammer.cloud.domain.K8sSpotInterruptionEvent;
import com.xammer.cloud.service.spot.WorkloadCapture.WorkloadEntry;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Recovery tracking of one {@code tracking} row against a new snapshot S1 (design §B4.4). Pure:
 * {@link #evaluate} decides, {@link #apply} writes the decision onto the row.
 *
 * <p>Recovered = the node is gone (no affected pod left on it) and every affected
 * Deployment/StatefulSet is back to {@code readyReplicas >= replicas} with no Pending pod. It is
 * measured at snapshot granularity and never estimated.
 */
public final class RecoveryEvaluator {

    /** The outcome of one evaluation. */
    public enum Outcome {
        /** S1 didn't observe a list the row depends on: leave the row untouched. */
        SKIP,
        /** Window expired with the node gone. */
        EXPIRED_NOT_OBSERVED,
        /** Window expired with the node still listed. */
        EXPIRED_NODE_NOT_REMOVED,
        /** Guard: no Deployment/StatefulSet to measure. */
        NOT_MEASURABLE,
        /** Every affected workload recovered in S1. */
        RECOVERED,
        /** Not yet recovered in S1. */
        UNRECOVERED
    }

    /**
     * @param outcome    what happened
     * @param pendingNow Pending pods of the affected workloads in S1 (only for RECOVERED/UNRECOVERED)
     */
    public record Evaluation(Outcome outcome, int pendingNow) {
        static Evaluation of(Outcome outcome) {
            return new Evaluation(outcome, 0);
        }

        public static Evaluation skip() {
            return of(Outcome.SKIP);
        }

        public static Evaluation notMeasurable() {
            return of(Outcome.NOT_MEASURABLE);
        }
    }

    private RecoveryEvaluator() {
    }

    /**
     * @param row       a {@code tracking} row (its {@code affected_workloads} is the evidence that each
     *                  stored workload existed at notice)
     * @param workloads the row's parsed workloads
     * @param s1        the new snapshot
     * @param t1        its server time
     * @param window    {@code recovery-window-hours}
     */
    public static Evaluation evaluate(K8sSpotInterruptionEvent row, List<WorkloadEntry> workloads,
                                      SnapshotView s1, Instant t1, Duration window) {
        String node = row.getNodeName();
        // Expiry doesn't depend on the workload lists, so a run of partial snapshots still ends.
        if (row.getNoticedAt() != null && !row.getNoticedAt().isAfter(t1.minus(window))) {
            return Evaluation.of(s1.listsNode(node) ? Outcome.EXPIRED_NODE_NOT_REMOVED : Outcome.EXPIRED_NOT_OBSERVED);
        }
        if (workloads == null) {
            return Evaluation.skip();
        }
        boolean hasDeployment = workloads.stream().anyMatch(w -> "Deployment".equals(w.kind()));
        boolean hasStatefulSet = workloads.stream().anyMatch(w -> "StatefulSet".equals(w.kind()));
        // Partial-snapshot guard: a missing or empty kind list in S1 is "not observed", never "deleted".
        if ((hasDeployment && !s1.deploymentsPresentAndNonEmpty())
                || (hasStatefulSet && !s1.statefulSetsPresentAndNonEmpty())) {
            return Evaluation.skip();
        }
        if (!hasDeployment && !hasStatefulSet) {
            return Evaluation.notMeasurable();
        }

        Set<WorkloadKeys.Key> affected = new HashSet<>();
        for (WorkloadEntry w : workloads) {
            affected.add(new WorkloadKeys.Key(w.namespace(), w.kind(), w.name()));
        }
        Set<WorkloadKeys.Key> pendingOwners = new HashSet<>();
        int pendingNow = 0;
        boolean onNode = false;
        for (SnapshotView.PodView pod : s1.pods()) {
            if ("DaemonSet".equals(pod.controllerKind())) {
                continue;
            }
            WorkloadKeys.Key key = WorkloadKeys.normalize(pod.namespace(), pod.controllerKind(),
                    pod.controllerName(), pod.name(), s1.deploymentKeys());
            if (!affected.contains(key)) {
                continue;
            }
            if ("Pending".equals(pod.phase())) {
                pendingNow++;
                pendingOwners.add(key);
            }
            if (node != null && node.equals(pod.nodeName()) && s1.listsNode(node)
                    && WorkloadCaptureBuilder.qualifies(pod)) {
                onNode = true;
            }
        }

        boolean allOk = true;
        for (WorkloadKeys.Key w : affected) {
            if (pendingOwners.contains(w)) {
                allOk = false;
                break;
            }
            if (WorkloadKeys.isMeasurable(w.kind())) {
                SnapshotView.WorkloadStatus status = "Deployment".equals(w.kind())
                        ? s1.deployments().get(w.namespace() + "/" + w.name())
                        : s1.statefulSets().get(w.namespace() + "/" + w.name());
                // Absent from S1 but in the stored list: it existed at notice and was deleted since → ok.
                if (status != null && status.readyReplicas() < status.replicas()) {
                    allOk = false;
                    break;
                }
            }
        }
        return new Evaluation(!onNode && allOk ? Outcome.RECOVERED : Outcome.UNRECOVERED, pendingNow);
    }

    /** Writes an evaluation onto the row. SKIP leaves every field untouched. */
    public static void apply(K8sSpotInterruptionEvent row, Evaluation evaluation, Instant t1) {
        switch (evaluation.outcome()) {
            case SKIP -> {
            }
            case EXPIRED_NOT_OBSERVED -> row.setRecoveryStatus(SpotInterruptionMerge.NOT_OBSERVED);
            case EXPIRED_NODE_NOT_REMOVED -> row.setRecoveryStatus(SpotInterruptionMerge.NODE_NOT_REMOVED);
            case NOT_MEASURABLE -> row.setRecoveryStatus(SpotInterruptionMerge.NOT_MEASURABLE);
            case RECOVERED, UNRECOVERED -> {
                if (evaluation.pendingNow() > 0) {
                    if (row.getPendingFirstSeenAt() == null) {
                        row.setPendingFirstSeenAt(t1);
                    }
                    row.setPendingLastSeenAt(t1);
                    row.setPendingMaxPods(Math.max(row.getPendingMaxPods() == null ? 0 : row.getPendingMaxPods(),
                            evaluation.pendingNow()));
                }
                if (evaluation.outcome() == Outcome.RECOVERED) {
                    row.setRecoveryStatus(SpotInterruptionMerge.RECOVERED);
                    row.setRecoveredObservedAt(t1);
                } else {
                    row.setRecoveryLastUnrecoveredAt(t1);
                }
            }
        }
    }
}
