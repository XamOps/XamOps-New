package com.xammer.cloud.service.automation.workload.api;

import com.xammer.cloud.service.automation.workload.ConfidenceProperties;
import com.xammer.cloud.service.automation.workload.ConfidenceScore;
import com.xammer.cloud.service.automation.workload.ContainerDecision;
import com.xammer.cloud.service.automation.workload.HorizontalDecision;
import com.xammer.cloud.service.automation.workload.Quantities;
import com.xammer.cloud.service.automation.workload.ResourceEnvelope;
import com.xammer.cloud.service.automation.workload.WorkloadAutoscalingProperties;
import com.xammer.cloud.service.automation.workload.WorkloadDecision;
import com.xammer.cloud.service.automation.workload.WorkloadPolicy;
import com.xammer.cloud.service.automation.workload.WorkloadSnapshot;
import com.xammer.cloud.service.automation.workload.api.dto.VerticalWorkloadDto;
import com.xammer.cloud.service.automation.workload.inventory.ClusterWorkloadInventory;
import com.xammer.cloud.service.automation.workload.inventory.UpstreamVpaReader;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Maps the engine's decision onto the published wire shape.
 *
 * <p>Pure and static, so it is testable directly against engine output over the measured reference
 * fixtures — which matters because the read endpoint has no usage source in this release and so
 * never exercises this path at runtime.
 *
 * <p>The one rule worth stating explicitly: <strong>an unset resource maps to {@code null}, never to
 * {@code 0}</strong>, and a workload with no recommendation maps {@code recommended} to
 * {@code null}, never to a copy of {@code current}.
 *
 * <h2>This is where auto-apply is gated</h2>
 *
 * The engine is pure and knows nothing about deployment state, so its {@code autoApplyEligible}
 * answers one question only: does the <em>policy</em> permit applying this. Whether this
 * installation may apply anything at all is a runtime fact, so the wire value is
 * {@code engine AND gate}. The {@link WorkloadAutoscalingProperties} parameter is
 * <strong>mandatory</strong> rather than an overload: a future call site cannot forget the gate,
 * because there is no signature that omits it.
 */
public final class VerticalDecisionMapper {

    /** The safe defaults (floor 0.5, high 0.8), used by the overloads that predate confidence. */
    private static final ConfidenceProperties DEFAULT_CONFIDENCE = new ConfidenceProperties(0.5, 0.8);

    private VerticalDecisionMapper() {
    }

    /**
     * @param gate the runtime gate; {@code autoApplyEligible} is {@code false} on the wire unless
     *             {@link WorkloadAutoscalingProperties#isAutoApplyPermitted()} agrees with the
     *             engine. Never null.
     */
    public static VerticalWorkloadDto toDto(WorkloadSnapshot snapshot, WorkloadPolicy policy,
                                            WorkloadDecision decision,
                                            WorkloadAutoscalingProperties gate) {
        return toDto(snapshot, policy, decision, gate, UpstreamVpaReader.View.none(), null,
                DEFAULT_CONFIDENCE);
    }

    /**
     * @param upstream upstream VPA's independently-computed targets, for the comparison column. Never
     *                 null — use {@link UpstreamVpaReader.View#none()} when no VPA read was made and
     *                 {@link UpstreamVpaReader.View#notReadable()} when the read was refused, so the
     *                 difference between "no VPA object" and "not allowed to look" reaches the UI
     *                 instead of both rendering as a blank.
     */
    public static VerticalWorkloadDto toDto(WorkloadSnapshot snapshot, WorkloadPolicy policy,
                                            WorkloadDecision decision,
                                            WorkloadAutoscalingProperties gate,
                                            UpstreamVpaReader.View upstream) {
        return toDto(snapshot, policy, decision, gate, upstream, null, DEFAULT_CONFIDENCE);
    }

    /**
     * @param lastAction the newest durable action recorded for this workload, or {@code null} when
     *                   none has ever been applied. The read service loads it once per cluster; the
     *                   {@code notEvaluated} path keeps it null.
     */
    public static VerticalWorkloadDto toDto(WorkloadSnapshot snapshot, WorkloadPolicy policy,
                                            WorkloadDecision decision,
                                            WorkloadAutoscalingProperties gate,
                                            UpstreamVpaReader.View upstream,
                                            VerticalWorkloadDto.LastActionDto lastAction) {
        return toDto(snapshot, policy, decision, gate, upstream, lastAction, DEFAULT_CONFIDENCE);
    }

    /**
     * @param confidence the band edges for the confidence chip. The band and {@code belowFloor} are
     *                   placed <strong>here</strong> — the only place the configurable cut points are
     *                   read — so the pure engine never sees them. Never null.
     */
    public static VerticalWorkloadDto toDto(WorkloadSnapshot snapshot, WorkloadPolicy policy,
                                            WorkloadDecision decision,
                                            WorkloadAutoscalingProperties gate,
                                            UpstreamVpaReader.View upstream,
                                            VerticalWorkloadDto.LastActionDto lastAction,
                                            ConfidenceProperties confidence) {
        Objects.requireNonNull(gate, "gate");
        Objects.requireNonNull(upstream, "upstream");
        Objects.requireNonNull(confidence, "confidence");
        Map<String, UpstreamVpaReader.ContainerTarget> targets = upstream.forWorkload(snapshot.ref());
        return new VerticalWorkloadDto(
                new VerticalWorkloadDto.WorkloadRefDto(
                        snapshot.ref().namespace(), snapshot.ref().kind(), snapshot.ref().name()),
                new VerticalWorkloadDto.PolicyDto(policy.mode().name(), policy.headroomPct(),
                        policy.percentile(), policy.windowLabel(), policy.applyMethod().name()),
                snapshot.replicas(),
                hpa(snapshot),
                snapshot.gitopsManaged(),
                decision.skipReason() == null ? null : decision.skipReason().name(),
                findings(decision, upstream),
                decision.containers().stream()
                        .map(container -> container(container, targets.get(container.containerName()),
                                podsDisagree(snapshot, container.containerName()), confidence))
                        .toList(),
                lastAction,                 // the newest recorded action, or null when none exists
                decision.autoApplyEligible() && gate.isAutoApplyPermitted(),
                provenance(decision),
                horizontal(decision, gate));
    }

    /**
     * A workload the engine could not evaluate at all, published with <strong>no numbers</strong>.
     *
     * <p>Used when the usage provider raised {@code INSUFFICIENT_HISTORY}: the policy and the gate
     * are known, the workload exists, and nothing about its sizing is. Containers are deliberately
     * empty rather than carrying current values with a null recommendation, because every other
     * field of a container row would then be a blank the reader has to interpret — the skip reason
     * says the whole story once.
     *
     * @param skipReason the shared reason code, e.g. {@code INSUFFICIENT_HISTORY}
     */
    public static VerticalWorkloadDto notEvaluated(ClusterWorkloadInventory.Workload workload,
                                                   WorkloadPolicy policy, String skipReason,
                                                   String usageSource, List<String> findings) {
        return new VerticalWorkloadDto(
                new VerticalWorkloadDto.WorkloadRefDto(
                        workload.ref().namespace(), workload.ref().kind(), workload.ref().name()),
                new VerticalWorkloadDto.PolicyDto(policy.mode().name(), policy.headroomPct(),
                        policy.percentile(), policy.windowLabel(), policy.applyMethod().name()),
                workload.replicas(),
                new VerticalWorkloadDto.HpaDto(workload.hpa() != null,
                        workload.hpa() == null ? null : workload.hpa().name()),
                null,                       // GitOps detection is deferred, so it stays unknown
                skipReason,
                findings == null ? List.of() : List.copyOf(findings),
                List.of(),
                null,
                false,
                // No window, no coverage: those are measurements, and none were obtained. The
                // percentile and window are the policy's, which is a fact about configuration.
                new VerticalWorkloadDto.ProvenanceDto(policy.percentile(), policy.windowHours(),
                        null, null, null, usageSource, policy.source().name()),
                new VerticalWorkloadDto.HorizontalDto(
                        HorizontalDecision.notEvaluated().state().name(), null, null, null, List.of()));
    }

    /** Engine findings plus any the read path observed, sorted and de-duplicated. */
    private static List<String> findings(WorkloadDecision decision, UpstreamVpaReader.View upstream) {
        return findings(decision, upstream.readable()
                ? List.of() : List.of(UpstreamVpaReader.FINDING_NOT_READABLE));
    }

    private static List<String> findings(WorkloadDecision decision, List<String> extra) {
        return Stream.concat(decision.findings().stream().map(Enum::name), extra.stream())
                .distinct().sorted().toList();
    }

    private static VerticalWorkloadDto.HpaDto hpa(WorkloadSnapshot snapshot) {
        var effective = snapshot.effectiveHpa();
        return new VerticalWorkloadDto.HpaDto(effective != null,
                effective == null ? null : effective.name());
    }

    private static VerticalWorkloadDto.ContainerDto container(ContainerDecision decision,
                                                              UpstreamVpaReader.ContainerTarget vpa,
                                                              boolean podsDisagree,
                                                              ConfidenceProperties confidence) {
        return new VerticalWorkloadDto.ContainerDto(
                decision.containerName(),
                decision.qosClass().wireName(),
                resources(decision.current()),
                resources(decision.recommended()),
                decision.reasons().stream().map(Enum::name).toList(),
                upstreamVpa(vpa),
                podsDisagree,
                confidence(decision.confidence(), confidence));
    }

    /**
     * Places the presentational band and {@code belowFloor} from the configured cut points. Null
     * when there is no confidence view (i.e. no recommendation) — the UI renders a dash, not a zero.
     */
    private static VerticalWorkloadDto.ConfidenceDto confidence(
            ContainerDecision.ConfidenceView view, ConfidenceProperties props) {
        if (view == null) {
            return null;
        }
        String band = ConfidenceScore.band(view.score(), props.floor(), props.highThreshold()).name();
        boolean belowFloor = view.score() < props.floor();
        return new VerticalWorkloadDto.ConfidenceDto(view.score(), band, view.sampleCoverage(),
                view.windowHours(), belowFloor);
    }

    private static boolean podsDisagree(WorkloadSnapshot snapshot, String containerName) {
        return snapshot.containers().stream()
                .anyMatch(container -> container.name().equals(containerName) && container.podsDisagree());
    }

    /** @return null when VPA has no target for this container — never a zero and never a copy of ours */
    private static VerticalWorkloadDto.UpstreamVpaDto upstreamVpa(UpstreamVpaReader.ContainerTarget vpa) {
        if (vpa == null) {
            return null;
        }
        return new VerticalWorkloadDto.UpstreamVpaDto(
                vpa.cpuMillis(),
                vpa.memBytes() == null ? null : Quantities.bytesToMiCeil(vpa.memBytes()),
                UpstreamVpaReader.SOURCE,
                vpa.observedAt());
    }

    /** @return null when the whole envelope is absent; individual unset fields stay null */
    private static VerticalWorkloadDto.ResourcesDto resources(ResourceEnvelope envelope) {
        if (envelope == null) {
            return null;
        }
        // Memory rounds UP to whole MiB. The contract's wire unit is MiB, and half-up sent a
        // sub-512KiB request to 0 -- a value the contract says never appears and the UI flags as a
        // bug. Up is also the safe direction: never below the real value, for either half of the pair.
        return new VerticalWorkloadDto.ResourcesDto(
                envelope.cpuRequestMillis(),
                envelope.memRequestBytes() == null ? null : Quantities.bytesToMiCeil(envelope.memRequestBytes()),
                envelope.cpuLimitMillis(),
                envelope.memLimitBytes() == null ? null : Quantities.bytesToMiCeil(envelope.memLimitBytes()));
    }

    private static VerticalWorkloadDto.ProvenanceDto provenance(WorkloadDecision decision) {
        var provenance = decision.provenance();
        return new VerticalWorkloadDto.ProvenanceDto(provenance.percentile(), provenance.windowHours(),
                provenance.windowStart(), provenance.windowEnd(), provenance.sampleCoverage(),
                provenance.usageSource(), provenance.policySource().name());
    }

    /**
     * The HPA decision block, built from the real {@link com.xammer.cloud.service.automation.workload.HpaDecision}.
     *
     * <p>While the horizontal master flag is off the state is downgraded to {@code NOT_EVALUATED}
     * with no numbers, so a UI built against the modelled contract keeps working. {@code autoApplyEligible}
     * is always false — HPA has no auto tier this task.
     */
    private static VerticalWorkloadDto.HorizontalDto horizontal(WorkloadDecision decision,
                                                                WorkloadAutoscalingProperties gate) {
        var hpa = decision.hpa();
        // The RPS advisory (F3) is read ONCE here and carried on BOTH branches. It is independent of
        // isHorizontalEnabled(): F3 is gated by its own RpsSizingProperties.enabled and defaults on
        // top of a default-off horizontal engine, so the early NOT_EVALUATED branch must still carry
        // it or the suggested-replicas line would always render a dash. A null advisory maps all
        // three rps* fields to null.
        var rps = decision.rps();
        Integer rpsReplicas = rps == null ? null : rps.replicas();
        String rpsReason = rps == null ? null : rps.reason().name();
        String rpsFormula = rps == null ? null : rps.formula();
        if (!gate.isHorizontalEnabled()) {
            return new VerticalWorkloadDto.HorizontalDto(
                    HorizontalDecision.State.NOT_EVALUATED.name(), null, null, null, List.of(),
                    null, false, rpsReplicas, rpsReason, rpsFormula);
        }
        return new VerticalWorkloadDto.HorizontalDto(hpa.state().name(),
                hpa.minReplicas(), hpa.maxReplicas(), hpa.targetUtilizationPct(),
                hpa.reasons().stream().map(Enum::name).toList(),
                hpa.formula(), false, rpsReplicas, rpsReason, rpsFormula);
    }
}
