package com.xammer.cloud.service.automation.workload.execute;

import com.xammer.cloud.service.automation.workload.ResourceEnvelope;

import java.util.List;

/**
 * The evidence for what a resize actually did — the record that makes "restart-free" a claim with a
 * proof behind it rather than a slogan.
 *
 * <h2>Why a richer outcome than {@link ActionOutcome.Terminal}</h2>
 *
 * The published audit vocabulary has five terminal states
 * ({@code APPLIED | PARTIALLY_APPLIED | REFUSED | FAILED | EXPIRED}) and is a contract the UI is
 * already built against, so it is not extended here. But "the kubelet deferred this until a node has
 * room", "the kubelet will never do this on this node", "the resize is still in progress at the
 * deadline because a memory decrease cannot complete" and "a container restarted" are four different
 * things an operator must be able to tell apart, and all four map onto {@code FAILED}. So the rich
 * {@link Outcome} is what the apply endpoint reports in its additive {@code result} block, and
 * {@link #publishedTerminal()} is the lossy projection onto the published five.
 *
 * <p>Note what {@link Outcome#DRY_RUN} maps to: {@code REFUSED}, whose published meaning is
 * "nothing was written". A rehearsal that reported {@code APPLIED} is precisely the measured
 * reference failure — {@code severity: "success"} / "Rebalance completed: 2 nodes drained" under
 * {@code DRY_RUN=true} — and no amount of a {@code dryRun} flag elsewhere in the payload undoes a
 * success claim.
 *
 * @param outcome     what happened, in full
 * @param detail      human-readable detail; mandatory for anything that is not {@link Outcome#APPLIED}
 * @param restartFree true only when every pod touched was <strong>proved</strong> not to have
 *                    restarted: same pod uid, same containerID, unchanged restartCount, still Ready.
 *                    False whenever the question was not answered, which is not the same as "it
 *                    restarted" and is why {@link #pods()} carries the raw numbers too.
 * @param containers  per-container result
 * @param pods        per-pod before/after proof, in the order the pods were touched (canary first)
 */
public record ResizeProof(Outcome outcome,
                          String detail,
                          boolean restartFree,
                          List<ContainerResult> containers,
                          List<PodProof> pods) {

    public ResizeProof {
        if (outcome == null) {
            throw new IllegalArgumentException("outcome is mandatory");
        }
        if (outcome != Outcome.APPLIED && (detail == null || detail.isBlank())) {
            throw new IllegalArgumentException(
                    "anything other than a plain success must say what happened, got " + outcome);
        }
        if (outcome != Outcome.APPLIED && restartFree) {
            // "restart-free" is a claim about a completed, confirmed resize. Attaching it to a
            // deferral or a failure would dress up a non-event as a guarantee.
            throw new IllegalArgumentException(
                    "restart-free may only be claimed for a confirmed APPLIED resize, got " + outcome);
        }
        containers = containers == null ? List.of() : List.copyOf(containers);
        pods = pods == null ? List.of() : List.copyOf(pods);
    }

    /** What actually happened. Every value except {@link #APPLIED} means nothing was confirmed. */
    public enum Outcome {
        /** Every target is in place on every pod, confirmed from {@code status}. */
        APPLIED,
        /** The canary succeeded and a later pod did not; {@link #pods()} says which. */
        PARTIALLY_APPLIED,
        /** A rehearsal. No patch was sent, so nothing was applied and nothing is claimed. */
        DRY_RUN,
        /** {@code PodResizePending / Deferred} — the kubelet is waiting for capacity. */
        DEFERRED,
        /** {@code PodResizePending / Infeasible} — the kubelet will never do this on this node. */
        INFEASIBLE,
        /** Still {@code PodResizeInProgress} at the deadline, the memory-decrease case. */
        STUCK_IN_PROGRESS,
        /** A container restarted when it was not supposed to. */
        RESTART_DETECTED,
        /** The resize landed but the pod is no longer Ready, so it is not a success. */
        NOT_READY,
        /** A safety rule declined before any patch was sent. */
        REFUSED,
        /** {@code notValidAfter} had passed. No patch was sent. */
        EXPIRED,
        /** Anything else went wrong; whether a patch landed may be unknown. */
        FAILED
    }

    /** One container's result, in the rich vocabulary. */
    public enum ContainerState {
        APPLIED, DEFERRED, INFEASIBLE, STUCK_IN_PROGRESS, RESTARTED, REFUSED, DRY_RUN,
        /** The canary failed first, so this container was deliberately never touched. */
        NOT_ATTEMPTED,
        FAILED
    }

    /** The lossy projection onto the published five-state audit vocabulary. */
    public ActionOutcome.Terminal publishedTerminal() {
        return switch (outcome) {
            case APPLIED -> ActionOutcome.Terminal.APPLIED;
            case PARTIALLY_APPLIED -> ActionOutcome.Terminal.PARTIALLY_APPLIED;
            case EXPIRED -> ActionOutcome.Terminal.EXPIRED;
            // Nothing was written in either case, which is what REFUSED publishes.
            case DRY_RUN, REFUSED -> ActionOutcome.Terminal.REFUSED;
            case DEFERRED, INFEASIBLE, STUCK_IN_PROGRESS, RESTART_DETECTED, NOT_READY, FAILED ->
                    ActionOutcome.Terminal.FAILED;
        };
    }

    public static ResizeProof refused(String detail) {
        return new ResizeProof(Outcome.REFUSED, detail, false, List.of(), List.of());
    }

    public static ResizeProof expired(String detail) {
        return new ResizeProof(Outcome.EXPIRED, detail, false, List.of(), List.of());
    }

    /**
     * @param requested      the numbers submitted for this container, so the record is readable
     *                       without a join back to the command
     * @param restartExpected true when the container declares {@code resizePolicy:
     *                        RestartContainer} for a resource being changed — the one case where a
     *                        restart is correct behaviour rather than a failure, and therefore the
     *                        one case where restart-free must not be promised
     */
    public record ContainerResult(String name, ContainerState state, String detail,
                                  ResourceEnvelope requested, boolean restartExpected) {

        public ContainerResult {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("container name is mandatory");
            }
            if (state == null) {
                throw new IllegalArgumentException("state is mandatory");
            }
        }

        public boolean applied() {
            return state == ContainerState.APPLIED;
        }
    }

    /**
     * One pod's before/after, which is the whole restart-free argument in data.
     *
     * @param uidBefore the pod's {@code metadata.uid} before the patch
     * @param uidAfter  the uid after; a different value means this is a <em>different pod</em> that
     *                  happens to share a name, so the resize was never applied to the pod that was
     *                  measured
     * @param ready     whether the pod's {@code Ready} condition was still True afterwards
     */
    public record PodProof(String podName, String uidBefore, String uidAfter, boolean ready,
                           List<ContainerProof> containers) {

        public PodProof {
            if (podName == null || podName.isBlank()) {
                throw new IllegalArgumentException("podName is mandatory");
            }
            containers = containers == null ? List.of() : List.copyOf(containers);
        }

        public boolean samePod() {
            return uidBefore != null && uidBefore.equals(uidAfter);
        }
    }

    /**
     * One container's before/after on one pod.
     *
     * @param observedBefore {@code status.containerStatuses[].resources} before the patch
     * @param observedAfter  the same field after it — the <strong>actual</strong> applied values,
     *                       never the spec
     */
    public record ContainerProof(String name,
                                 String containerIdBefore, String containerIdAfter,
                                 Integer restartCountBefore, Integer restartCountAfter,
                                 ResourceEnvelope observedBefore, ResourceEnvelope observedAfter) {

        public ContainerProof {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("container name is mandatory");
            }
        }

        /** The container was never replaced and never restarted. */
        public boolean sameContainer() {
            return containerIdBefore != null && containerIdBefore.equals(containerIdAfter)
                    && restartCountBefore != null && restartCountBefore.equals(restartCountAfter);
        }
    }
}
