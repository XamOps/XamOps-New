package com.xammer.cloud.service.automation.workload.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * One workload, shaped as the UI design's {@code VerticalWorkload} view model.
 *
 * @param ref               identity
 * @param policy            the resolved policy — a row's values, or the defaults
 * @param replicas          as configured
 * @param hpa               whether an HPA targets this workload
 * @param gitopsManaged     null until GitOps detection is implemented
 * @param skipReason        a gate's name, or null
 * @param findings          observations; a finding never implies a recommendation
 * @param containers        per-container decisions
 * @param lastAction        the last applied action, or null
 * @param autoApplyEligible whether this may be applied without a human naming the numbers
 * @param provenance        where the numbers came from (addition to §11.5)
 * @param horizontal        modelled; {@code NOT_EVALUATED} in this release (addition to §11.5)
 * @param cost              real {@code $} cost + savings (FEAT-001), or null when the cost feature is
 *                          off / this workload could not be priced
 */
public record VerticalWorkloadDto(WorkloadRefDto ref,
                                  PolicyDto policy,
                                  Integer replicas,
                                  HpaDto hpa,
                                  Boolean gitopsManaged,
                                  String skipReason,
                                  List<String> findings,
                                  List<ContainerDto> containers,
                                  LastActionDto lastAction,
                                  boolean autoApplyEligible,
                                  ProvenanceDto provenance,
                                  HorizontalDto horizontal,
                                  WorkloadCostDto cost) {

    public VerticalWorkloadDto {
        findings = findings == null ? List.of() : List.copyOf(findings);
        containers = containers == null ? List.of() : List.copyOf(containers);
    }

    /** The pre-cost shape: {@code cost} delegates to null, for callers built before FEAT-001. */
    public VerticalWorkloadDto(WorkloadRefDto ref, PolicyDto policy, Integer replicas, HpaDto hpa,
                               Boolean gitopsManaged, String skipReason, List<String> findings,
                               List<ContainerDto> containers, LastActionDto lastAction,
                               boolean autoApplyEligible, ProvenanceDto provenance,
                               HorizontalDto horizontal) {
        this(ref, policy, replicas, hpa, gitopsManaged, skipReason, findings, containers, lastAction,
                autoApplyEligible, provenance, horizontal, null);
    }

    /** This workload with its cost annotation attached, every other field unchanged. */
    public VerticalWorkloadDto withCost(WorkloadCostDto cost) {
        return new VerticalWorkloadDto(ref, policy, replicas, hpa, gitopsManaged, skipReason, findings,
                containers, lastAction, autoApplyEligible, provenance, horizontal, cost);
    }

    public record WorkloadRefDto(String namespace, String kind, String name) {
    }

    /** @param window the policy window in the API's vocabulary, e.g. {@code "24h"} */
    public record PolicyDto(String mode, Integer headroomPct, Integer percentile,
                            String window, String applyMethod) {
    }

    public record HpaDto(boolean present, String name) {
    }

    /**
     * @param qosClass    Kubernetes' spelling: {@code Guaranteed}, {@code Burstable}, {@code BestEffort}
     * @param current     present resources; <strong>null fields mean unset, never 0</strong>
     * @param recommended the decided resources, or <strong>null when nothing is recommended</strong>
     *                    — never a copy of {@code current}
     * @param reasons     why, including why nothing moved
     * @param upstreamVpa what upstream VPA independently recommends for this container, or
     *                    <strong>null</strong> when there is no VPA object, the optional ClusterRole
     *                    rule was not granted, or VPA has not produced a target. An addition to
     *                    §11.5; the UI renders {@code —} rather than a number when it is null.
     * @param podsDisagree the live pods run different resources for this container right now, so
     *                    {@code current} is the workload's own size (what new pods start at), not
     *                    what every pod runs (review-2 SF2)
     */
    public record ContainerDto(String name, String qosClass, ResourcesDto current,
                               ResourcesDto recommended, List<String> reasons,
                               UpstreamVpaDto upstreamVpa, boolean podsDisagree,
                               ConfidenceDto confidence) {

        public ContainerDto {
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
        }

        /** A container whose live pods agree, before confidence was carried. */
        public ContainerDto(String name, String qosClass, ResourcesDto current, ResourcesDto recommended,
                            List<String> reasons, UpstreamVpaDto upstreamVpa) {
            this(name, qosClass, current, recommended, reasons, upstreamVpa, false, null);
        }

        /** The pre-confidence shape with an explicit podsDisagree flag. */
        public ContainerDto(String name, String qosClass, ResourcesDto current, ResourcesDto recommended,
                            List<String> reasons, UpstreamVpaDto upstreamVpa, boolean podsDisagree) {
            this(name, qosClass, current, recommended, reasons, upstreamVpa, podsDisagree, null);
        }
    }

    /**
     * The confidence annotation on a recommended container, or {@code null} when there is no
     * recommendation — the UI renders a dash, never a zero.
     *
     * @param score          the {@code [0,1]} measured score
     * @param band           {@code HIGH}/{@code MEDIUM}/{@code LOW}, placed in the mapper from the
     *                       configurable cut points
     * @param sampleCoverage the coverage the score used, for the chip's "why" line
     * @param windowHours    the window the measurement covered
     * @param belowFloor     UI chip-tone only: {@code score < floor}. <strong>Never read by any gate
     *                       or by {@code autoApplyEligible}.</strong>
     */
    public record ConfidenceDto(double score, String band, double sampleCoverage, int windowHours,
                                boolean belowFloor) {
    }

    /**
     * Upstream VPA's own target for one container, for side-by-side comparison.
     *
     * <p>Same units as {@link ResourcesDto}: CPU in millicores, memory in MiB. A null field means VPA
     * did not target that resource — never {@code 0}.
     *
     * @param source     always {@code "vpa"}, so the origin of the number is on the wire with it
     * @param observedAt when VPA last provided the recommendation, or null when it does not say
     */
    public record UpstreamVpaDto(Integer cpuRequestM, Long memRequestMi, String source,
                                 Instant observedAt) {
    }

    /**
     * CPU in millicores, memory in MiB.
     *
     * <p><strong>{@code null} means unset. It is never {@code 0}.</strong> The measured Go output
     * reported {@code "0m"} and {@code "1Mi"} as the current state of a container that had neither,
     * then "fixed" that fabricated baseline for 0% savings. A {@code 0} here would mean "a request of
     * zero", which Kubernetes does not express and this engine cannot produce.
     */
    public record ResourcesDto(Integer cpuRequestM, Long memRequestMi,
                               Integer cpuLimitM, Long memLimitMi) {
    }

    /** @param dryRun always present on an action record, never omitted or defaulted */
    public record LastActionDto(Instant at, Boolean dryRun, String outcome) {
    }

    public record ProvenanceDto(Integer percentile, Integer windowHours,
                                Instant windowStart, Instant windowEnd,
                                Double sampleCoverage, String usageSource, String policySource) {
    }

    /**
     * The HPA decision, shaped for the UI.
     *
     * <p>{@code null} on {@code minReplicas} / {@code maxReplicas} / {@code targetCpuUtilizationPct}
     * means "leave that field alone"; a diff against the current HPA is what an apply patches.
     *
     * @param formula            the human-readable derivation, e.g.
     *                           {@code "ceil(5 × 64% ÷ 50%) = 7, capped to 8"}, or null
     * @param autoApplyEligible  always false — HPA has no auto tier this task, forced at the mapper
     * @param rpsReplicas        the RPS-based suggested replica count (F3), or {@code null} in the
     *                           two dash outcomes. Carried independently of the HPA half: it travels
     *                           on <strong>both</strong> mapper branches regardless of whether the
     *                           horizontal engine is enabled, because F3 is a separate default-off
     *                           feature. A {@code null} here renders a dash, never a zero.
     * @param rpsReason          {@code RPS_SIZED} / {@code NO_RPS_SOURCE} / {@code NO_CAPACITY_BASIS},
     *                           or {@code null} when the feature did not run
     * @param rpsFormula         the RPS sizing derivation string, non-null only when
     *                           {@code rpsReplicas} is
     */
    public record HorizontalDto(String state, Integer minReplicas, Integer maxReplicas,
                                Integer targetCpuUtilizationPct, List<String> reasons,
                                String formula, boolean autoApplyEligible,
                                Integer rpsReplicas, String rpsReason, String rpsFormula) {

        public HorizontalDto {
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
        }

        /**
         * The HPA shape before F3, with no RPS advisory. The three {@code rps*} fields default to
         * {@code null}. The mapper never uses this for a real decision — it calls the 10-arg
         * canonical constructor directly on both branches with the real {@code rps*} — so this form
         * is correct only on the engine-never-ran {@code notEvaluated(...)} path.
         */
        public HorizontalDto(String state, Integer minReplicas, Integer maxReplicas,
                             Integer targetCpuUtilizationPct, List<String> reasons,
                             String formula, boolean autoApplyEligible) {
            this(state, minReplicas, maxReplicas, targetCpuUtilizationPct, reasons, formula,
                    autoApplyEligible, null, null, null);
        }

        /** The pre-HPA shape (no formula, never auto-appliable), for the modelled / not-evaluated path. */
        public HorizontalDto(String state, Integer minReplicas, Integer maxReplicas,
                             Integer targetCpuUtilizationPct, List<String> reasons) {
            this(state, minReplicas, maxReplicas, targetCpuUtilizationPct, reasons, null, false,
                    null, null, null);
        }
    }
}
