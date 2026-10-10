package com.xammer.cloud.service.automation.workload.api;

import com.xammer.cloud.service.automation.workload.ContainerDecision;
import com.xammer.cloud.service.automation.workload.Quantities;
import com.xammer.cloud.service.automation.workload.ResourceEnvelope;
import com.xammer.cloud.service.automation.workload.WorkloadAutoscalingProperties;
import com.xammer.cloud.service.automation.workload.WorkloadDecision;
import com.xammer.cloud.service.automation.workload.WorkloadPolicy;
import com.xammer.cloud.service.automation.workload.WorkloadSnapshot;
import com.xammer.cloud.service.automation.workload.api.dto.VerticalWorkloadDto;

import java.util.List;
import java.util.Objects;

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
        Objects.requireNonNull(gate, "gate");
        return new VerticalWorkloadDto(
                new VerticalWorkloadDto.WorkloadRefDto(
                        snapshot.ref().namespace(), snapshot.ref().kind(), snapshot.ref().name()),
                new VerticalWorkloadDto.PolicyDto(policy.mode().name(), policy.headroomPct(),
                        policy.percentile(), policy.windowLabel(), policy.applyMethod().name()),
                snapshot.replicas(),
                hpa(snapshot),
                snapshot.gitopsManaged(),
                decision.skipReason() == null ? null : decision.skipReason().name(),
                decision.findings().stream().map(Enum::name).sorted().toList(),
                decision.containers().stream().map(VerticalDecisionMapper::container).toList(),
                null,                       // no action has ever been applied: no executor exists
                decision.autoApplyEligible() && gate.isAutoApplyPermitted(),
                provenance(decision),
                horizontal(decision));
    }

    private static VerticalWorkloadDto.HpaDto hpa(WorkloadSnapshot snapshot) {
        var effective = snapshot.effectiveHpa();
        return new VerticalWorkloadDto.HpaDto(effective != null,
                effective == null ? null : effective.name());
    }

    private static VerticalWorkloadDto.ContainerDto container(ContainerDecision decision) {
        return new VerticalWorkloadDto.ContainerDto(
                decision.containerName(),
                decision.qosClass().wireName(),
                resources(decision.current()),
                resources(decision.recommended()),
                decision.reasons().stream().map(Enum::name).toList());
    }

    /** @return null when the whole envelope is absent; individual unset fields stay null */
    private static VerticalWorkloadDto.ResourcesDto resources(ResourceEnvelope envelope) {
        if (envelope == null) {
            return null;
        }
        return new VerticalWorkloadDto.ResourcesDto(
                envelope.cpuRequestMillis(),
                envelope.memRequestBytes() == null ? null : Quantities.bytesToMi(envelope.memRequestBytes()),
                envelope.cpuLimitMillis(),
                envelope.memLimitBytes() == null ? null : Quantities.bytesToMi(envelope.memLimitBytes()));
    }

    private static VerticalWorkloadDto.ProvenanceDto provenance(WorkloadDecision decision) {
        var provenance = decision.provenance();
        return new VerticalWorkloadDto.ProvenanceDto(provenance.percentile(), provenance.windowHours(),
                provenance.windowStart(), provenance.windowEnd(), provenance.sampleCoverage(),
                provenance.usageSource(), provenance.policySource().name());
    }

    private static VerticalWorkloadDto.HorizontalDto horizontal(WorkloadDecision decision) {
        var horizontal = decision.horizontal();
        return new VerticalWorkloadDto.HorizontalDto(horizontal.state().name(),
                horizontal.minReplicas(), horizontal.maxReplicas(),
                horizontal.targetCpuUtilizationPct(), List.copyOf(horizontal.reasons()));
    }
}
