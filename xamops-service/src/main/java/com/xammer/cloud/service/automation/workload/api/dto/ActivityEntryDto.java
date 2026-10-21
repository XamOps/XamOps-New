package com.xammer.cloud.service.automation.workload.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * One recorded automation action, as the activity feed serves it. Every field the row does not
 * hold is null, never a guess.
 *
 * @param id          the row id
 * @param at          when it happened ({@code applied_at})
 * @param feature     {@code VERTICAL} (the Workload autoscaler, including a mode change) or
 *                    {@code HPA}
 * @param origin      {@code MANUAL} (a user) or {@code AUTO} (XamOps's automatic loop)
 * @param who         "A user" or "XamOps (automatic)". The user's name is not stored
 * @param action      {@code RESIZE}, {@code HPA_CHANGE} or {@code MODE_CHANGE}
 * @param dryRun      a test run: nothing was sent to the cluster
 * @param outcome     the stored outcome, verbatim
 * @param result      {@code APPLIED | PUT_BACK | REFUSED | TEST_RUN | FAILED}; null for a mode change
 *                    (not an apply) and for an outcome this build does not know
 * @param terminal    the executor's full outcome ({@code DEFERRED}, {@code RESTART_DETECTED}, …) on a
 *                    row written since the activity log existed; null on older rows and HPA rows
 * @param restartFree true only when every pod touched was proved not to have restarted
 * @param change      what changed; null when the row records nothing to show
 * @param proof       the per-pod restart-free evidence; null when the row holds none
 * @param detail      the stored detail: what was done and seen, or the refusal's code and message
 * @param reference   the command's correlation id, when the row has one
 */
public record ActivityEntryDto(Long id, Instant at, String feature, String origin, String who,
                               String action, Target target, boolean dryRun, String outcome,
                               String result, String terminal, boolean restartFree, Change change,
                               Proof proof, String detail, String reference) {

    public record Target(String namespace, String kind, String name) {
    }

    /** Exactly one of the three is set. */
    public record Change(List<ContainerChange> containers, HpaChange hpa, ModeChange mode) {
    }

    /**
     * One container's sizes.
     *
     * @param before      the size before the apply: observed on the pod ({@code OBSERVED}) or, on a
     *                    row from before the activity log, the submitted target ({@code REQUESTED})
     * @param beforeBasis {@code OBSERVED} or {@code REQUESTED}; null with {@code before}
     * @param after       the size observed on the pod afterwards (after a put-back, the put-back size)
     * @param requested   the size the apply asked for
     */
    public record ContainerChange(String container, Resources before, String beforeBasis,
                                  Resources after, Resources requested) {
    }

    public record Resources(Integer cpuRequestMillis, Integer cpuLimitMillis, Long memRequestBytes,
                            Long memLimitBytes) {
    }

    public record HpaChange(HpaBounds before, HpaBounds after) {
    }

    public record HpaBounds(Integer minReplicas, Integer maxReplicas, Integer targetUtilizationPct) {
    }

    public record ModeChange(String from, String to) {
    }

    /**
     * @param reverted null when no pod needed putting back; otherwise whether every one was put back
     *                 and confirmed
     */
    public record Proof(boolean restartFree, Boolean reverted, List<PodProof> pods) {
    }

    /**
     * @param node       the pod's node; the proof does not record it, so it is null today
     * @param changed    the pod ended at a size other than it had before: false for a pod put back
     *                   or never touched; null on a row written before the proof said so
     * @param uidChanged a different pod uid afterwards: the pod was replaced, not resized
     */
    public record PodProof(String pod, String node, Boolean changed, Boolean uidChanged, Integer restartsBefore,
                           Integer restartsAfter, Boolean ready, Revert revert) {
    }

    public record Revert(String state, String detail) {
    }
}
