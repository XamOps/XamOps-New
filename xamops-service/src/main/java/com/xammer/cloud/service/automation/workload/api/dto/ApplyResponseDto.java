package com.xammer.cloud.service.automation.workload.api.dto;

import com.xammer.cloud.service.automation.workload.Quantities;
import com.xammer.cloud.service.automation.workload.ResourceEnvelope;
import com.xammer.cloud.service.automation.workload.execute.ResizeProof;

import java.util.ArrayList;
import java.util.List;

/**
 * The apply endpoint's {@code 202} body.
 *
 * <p>The first three fields are the published contract (api-contract.md §3.2) and are
 * <strong>verbatim</strong>, because the UI and a parallel workflow are already built against them:
 *
 * <pre>{@code { "correlationId": "c0ffee…", "state": "ACCEPTED", "dryRun": true } }</pre>
 *
 * <h2>{@code result} is additive, and it is real</h2>
 *
 * The contract's shape is asynchronous by design — the primary execution path is a command queue the
 * in-cluster agent drains, so an outcome genuinely arrives later. The <em>direct</em> path completes
 * inside the request, so withholding the outcome the server already has would be a worse lie than
 * the one the async shape protects against. {@code result} is therefore added rather than the
 * contract being renamed, and it is <strong>{@code null} when the outcome is not known</strong> —
 * never a guess, and never an optimistic default.
 *
 * <p>What it carries is the evidence, not an adjective: the method actually used, the rich outcome
 * ({@code APPLIED}, {@code DEFERRED}, {@code INFEASIBLE}, {@code STUCK_IN_PROGRESS},
 * {@code RESTART_DETECTED}, {@code DRY_RUN} …), the per-container state, and the restart-free proof
 * per pod — uid before and after, {@code containerID} before and after, {@code restartCount} before
 * and after, readiness, and the resources <em>observed in the pod's status</em>. A reader can check
 * the claim rather than take it.
 *
 * @param correlationId the idempotency key, echoed
 * @param state         {@code ACCEPTED | DUPLICATE | REFUSED}
 * @param dryRun        echoed from the request; always present, never defaulted
 * @param result        the real outcome, or null when it is genuinely not known
 */
public record ApplyResponseDto(String correlationId, String state, boolean dryRun, Result result) {

    public static ApplyResponseDto of(String correlationId, String state, boolean dryRun,
                                      String method, ResizeProof proof) {
        return new ApplyResponseDto(correlationId, state, dryRun,
                proof == null ? null : Result.of(method, proof));
    }

    /**
     * @param restartFree  true only when every pod touched was proved not to have restarted. False
     *                     also covers "the question was not answered", which is why {@code pods}
     *                     carries the raw numbers.
     * @param reverted     null when no pod needed putting back; true when every pod whose PATCH was
     *                     accepted but whose new size was not put in place was put back and proved
     *                     back; false when at least one was not, so its new size may still be
     *                     applied later
     * @param insufficient {@code cpu} or {@code memory} when the kubelet declined a pod for lack of
     *                     that resource on its node, else null
     */
    public record Result(String method, String terminal, String detail, boolean restartFree,
                         List<ContainerResultDto> containers, List<PodProofDto> pods,
                         Boolean reverted, String insufficient) {

        /** A result with nothing put back. */
        public Result(String method, String terminal, String detail, boolean restartFree,
                      List<ContainerResultDto> containers, List<PodProofDto> pods) {
            this(method, terminal, detail, restartFree, containers, pods, null, null);
        }

        static Result of(String method, ResizeProof proof) {
            List<ContainerResultDto> containers = new ArrayList<>();
            for (ResizeProof.ContainerResult container : proof.containers()) {
                containers.add(new ContainerResultDto(container.name(), container.state().name(),
                        container.detail(), resources(container.requested()),
                        container.restartExpected()));
            }
            List<PodProofDto> pods = new ArrayList<>();
            for (ResizeProof.PodProof pod : proof.pods()) {
                List<ContainerProofDto> proofs = new ArrayList<>();
                for (ResizeProof.ContainerProof container : pod.containers()) {
                    proofs.add(new ContainerProofDto(container.name(),
                            container.containerIdBefore(), container.containerIdAfter(),
                            container.restartCountBefore(), container.restartCountAfter(),
                            resources(container.observedBefore()), resources(container.observedAfter())));
                }
                pods.add(new PodProofDto(pod.podName(), pod.uidBefore(), pod.uidAfter(), pod.samePod(),
                        pod.ready(), proofs, pod.changed(),
                        pod.revert() == null ? null
                                : new RevertDto(pod.revert().state().name(), pod.revert().detail())));
            }
            return new Result(method, proof.outcome().name(), proof.detail(), proof.restartFree(),
                    List.copyOf(containers), List.copyOf(pods), proof.reverted(),
                    proof.insufficientResource());
        }
    }

    /**
     * What putting a declined pod back achieved.
     *
     * @param state {@code REVERTED | NOT_CONFIRMED | FAILED}
     */
    public record RevertDto(String state, String detail) {
    }

    /** @param state the rich per-container state, e.g. {@code APPLIED}, {@code DEFERRED} */
    public record ContainerResultDto(String name, String state, String detail,
                                     VerticalWorkloadDto.ResourcesDto requested,
                                     boolean restartExpected) {
    }

    /**
     * @param uidBefore the pod's uid before the patch
     * @param uidAfter  the uid after; a different value means a different pod, so the resize was
     *                  never applied to the pod that was measured
     * @param samePod   {@code uidBefore.equals(uidAfter)}, computed once so a reader does not have to
     * @param changed   whether the observed resources differ between before and the final state
     * @param revert    for a pod that had to be put back, what that achieved; null otherwise
     */
    public record PodProofDto(String podName, String uidBefore, String uidAfter, boolean samePod,
                              boolean ready, List<ContainerProofDto> containers, boolean changed,
                              RevertDto revert) {

        /** A pod nothing had to be put back on; {@code changed} is derived from the numbers. */
        public PodProofDto(String podName, String uidBefore, String uidAfter, boolean samePod,
                           boolean ready, List<ContainerProofDto> containers) {
            this(podName, uidBefore, uidAfter, samePod, ready, containers,
                    containers != null && containers.stream().anyMatch(c -> c.observedBefore() != null
                            && c.observedAfter() != null && !c.observedBefore().equals(c.observedAfter())),
                    null);
        }
    }

    /** @param observedAfter read from {@code status.containerStatuses[].resources}, not from the spec */
    public record ContainerProofDto(String name, String containerIdBefore, String containerIdAfter,
                                    Integer restartCountBefore, Integer restartCountAfter,
                                    VerticalWorkloadDto.ResourcesDto observedBefore,
                                    VerticalWorkloadDto.ResourcesDto observedAfter) {
    }

    /**
     * Bytes to whole MiB, the wire unit — <strong>rounded up</strong>, the same conversion the read
     * endpoint uses, so a positive request can never publish as {@code 0}.
     */
    static VerticalWorkloadDto.ResourcesDto resources(ResourceEnvelope envelope) {
        if (envelope == null) {
            return null;
        }
        return new VerticalWorkloadDto.ResourcesDto(
                envelope.cpuRequestMillis(),
                envelope.memRequestBytes() == null ? null : Quantities.bytesToMiCeil(envelope.memRequestBytes()),
                envelope.cpuLimitMillis(),
                envelope.memLimitBytes() == null ? null : Quantities.bytesToMiCeil(envelope.memLimitBytes()));
    }
}
