package com.xammer.cloud.service.automation.workload.api;

import com.xammer.cloud.service.automation.workload.ContainerSnapshot;
import com.xammer.cloud.service.automation.workload.ContainerUsageFixtures;
import com.xammer.cloud.service.automation.workload.NodeCapacity;
import com.xammer.cloud.service.automation.workload.ResourceEnvelope;
import com.xammer.cloud.service.automation.workload.WorkloadAutoscalingEngine;
import com.xammer.cloud.service.automation.workload.WorkloadAutoscalingProperties;
import com.xammer.cloud.service.automation.workload.WorkloadDecision;
import com.xammer.cloud.service.automation.workload.WorkloadMode;
import com.xammer.cloud.service.automation.workload.WorkloadPolicy;
import com.xammer.cloud.service.automation.workload.WorkloadPolicyDefaults;
import com.xammer.cloud.service.automation.workload.WorkloadRef;
import com.xammer.cloud.service.automation.workload.WorkloadSnapshot;
import com.xammer.cloud.service.automation.workload.api.dto.VerticalWorkloadDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The engine-to-wire mapping, proven directly against engine output.
 *
 * <p>Tested here rather than through the read endpoint because the endpoint has no usage source in
 * this release and so never reaches this code at runtime. The mapping is still part of the published
 * contract, and the properties that matter most — <strong>unset is {@code null}, never {@code 0}</strong>,
 * and <strong>no recommendation is {@code null}, never a copy of {@code current}</strong> — are
 * exactly the ones the measured Go output got wrong.
 */
class VerticalDecisionMapperTest {

    private static final long MI = 1024L * 1024L;

    /** Both flags on — the most permissive gate this release can be configured into. */
    private static final WorkloadAutoscalingProperties GATE =
            new WorkloadAutoscalingProperties(true, true);

    private final WorkloadAutoscalingEngine engine =
            new WorkloadAutoscalingEngine(Clock.fixed(ContainerUsageFixtures.NOW, ZoneOffset.UTC));
    private final WorkloadPolicy policy = WorkloadPolicyDefaults.policy(WorkloadMode.RECOMMEND);

    @Test
    @DisplayName("a floored decrease maps to millicores and MiB, with limits moved")
    void aDecreaseMapsToTheWireUnits() {
        WorkloadSnapshot snapshot = guaranteedOverprovisioned();
        WorkloadDecision decision = engine.decide(snapshot, policy, NodeCapacity.unbounded());

        VerticalWorkloadDto dto = VerticalDecisionMapper.toDto(snapshot, policy, decision, GATE);

        assertThat(dto.ref().namespace()).isEqualTo("team-a");
        assertThat(dto.ref().kind()).isEqualTo("Deployment");
        assertThat(dto.ref().name()).isEqualTo("overprovisioned-api");
        assertThat(dto.replicas()).isEqualTo(2);
        assertThat(dto.skipReason()).isNull();
        assertThat(dto.hpa().present()).isFalse();
        assertThat(dto.hpa().name()).isNull();

        VerticalWorkloadDto.ContainerDto container = dto.containers().get(0);
        assertThat(container.qosClass()).isEqualTo("Guaranteed");
        assertThat(container.current().cpuRequestM()).isEqualTo(1000);
        assertThat(container.current().memRequestMi()).isEqualTo(1024);
        assertThat(container.recommended().cpuRequestM()).isEqualTo(25);
        assertThat(container.recommended().memRequestMi()).isEqualTo(250);
        assertThat(container.recommended().cpuLimitM()).isEqualTo(25);
        assertThat(container.recommended().memLimitMi()).isEqualTo(250);
        assertThat(container.reasons()).contains("FLOOR_CLAMPED");
    }

    @Test
    @DisplayName("an unset resource maps to null, never to 0")
    void unsetMapsToNull() {
        WorkloadRef ref = WorkloadRef.of("dev-sim", "Deployment", "no-requests-sidecar-app");
        WorkloadSnapshot snapshot = new WorkloadSnapshot(ref, 2, false, false, null, null, null, List.of(
                new ContainerSnapshot("app", ResourceEnvelope.UNSET,
                        ContainerUsageFixtures.usage("app", 120, 300 * MI))));
        WorkloadDecision decision = engine.decide(snapshot, policy, NodeCapacity.unbounded());

        VerticalWorkloadDto dto = VerticalDecisionMapper.toDto(snapshot, policy, decision, GATE);
        VerticalWorkloadDto.ContainerDto container = dto.containers().get(0);

        assertThat(container.qosClass()).isEqualTo("BestEffort");
        assertThat(container.current().cpuRequestM()).isNull();
        assertThat(container.current().memRequestMi()).isNull();
        assertThat(container.current().cpuLimitM()).isNull();
        assertThat(container.current().memLimitMi()).isNull();
        assertThat(dto.findings()).contains("REQUESTS_UNSET");
        assertThat(dto.autoApplyEligible()).isFalse();

        // The advisory carries no invented limit: that would change the QoS class.
        assertThat(container.recommended().cpuRequestM()).isEqualTo(150);
        assertThat(container.recommended().cpuLimitM()).isNull();
    }

    @Test
    @DisplayName("no recommendation maps to null, never to a copy of current")
    void noRecommendationMapsToNull() {
        WorkloadRef ref = WorkloadRef.of("prod-sim", "Deployment", "rightsized-worker");
        WorkloadSnapshot snapshot = new WorkloadSnapshot(ref, 3, false, false, null, null, null, List.of(
                new ContainerSnapshot("worker", new ResourceEnvelope(80, null, 64 * MI, null),
                        ContainerUsageFixtures.usage("worker", 48, 41 * MI))));
        WorkloadDecision decision = engine.decide(snapshot, policy, NodeCapacity.unbounded());

        VerticalWorkloadDto dto = VerticalDecisionMapper.toDto(snapshot, policy, decision, GATE);
        VerticalWorkloadDto.ContainerDto container = dto.containers().get(0);

        assertThat(container.recommended())
                .as("a caller must be able to tell 'no change' from 'change to the same value'")
                .isNull();
        assertThat(container.reasons()).isNotEmpty();
    }

    @Test
    @DisplayName("a gated workload carries its reason and no containers at all")
    void aGatedWorkloadCarriesNoContainers() {
        WorkloadRef ref = WorkloadRef.of("kube-system", "Deployment", "coredns");
        WorkloadSnapshot snapshot = new WorkloadSnapshot(ref, 2, false, false, null, null, null, List.of(
                new ContainerSnapshot("coredns", new ResourceEnvelope(100, null, 128 * MI, null),
                        ContainerUsageFixtures.usage("coredns", 50, 64 * MI))));
        WorkloadDecision decision = engine.decide(snapshot, policy, NodeCapacity.unbounded());

        VerticalWorkloadDto dto = VerticalDecisionMapper.toDto(snapshot, policy, decision, GATE);

        assertThat(dto.skipReason()).isEqualTo("PROTECTED_NAMESPACE");
        assertThat(dto.containers()).isEmpty();
    }

    @Test
    @DisplayName("provenance and the policy are reported on every workload")
    void provenanceAndPolicyAreReported() {
        WorkloadSnapshot snapshot = guaranteedOverprovisioned();
        WorkloadDecision decision = engine.decide(snapshot, policy, NodeCapacity.unbounded());

        VerticalWorkloadDto dto = VerticalDecisionMapper.toDto(snapshot, policy, decision, GATE);

        assertThat(dto.policy().mode()).isEqualTo("RECOMMEND");
        assertThat(dto.policy().percentile()).isEqualTo(95);
        assertThat(dto.policy().window()).isEqualTo("24h");
        assertThat(dto.policy().headroomPct()).isEqualTo(25);
        assertThat(dto.policy().applyMethod()).isEqualTo("IN_PLACE");

        assertThat(dto.provenance().percentile()).isEqualTo(95);
        assertThat(dto.provenance().windowHours()).isEqualTo(24);
        assertThat(dto.provenance().sampleCoverage()).isEqualTo(1.0);
        assertThat(dto.provenance().usageSource()).isEqualTo("test");
        assertThat(dto.provenance().policySource()).isEqualTo("DEFAULTS");
    }

    @Test
    @DisplayName("the horizontal half is reported as NOT_EVALUATED with no numbers")
    void theHorizontalHalfIsReportedHonestly() {
        WorkloadSnapshot snapshot = guaranteedOverprovisioned();
        WorkloadDecision decision = engine.decide(snapshot, policy, NodeCapacity.unbounded());

        VerticalWorkloadDto dto = VerticalDecisionMapper.toDto(snapshot, policy, decision, GATE);

        assertThat(dto.horizontal().state()).isEqualTo("NOT_EVALUATED");
        assertThat(dto.horizontal().minReplicas()).isNull();
        assertThat(dto.horizontal().maxReplicas()).isNull();
        assertThat(dto.horizontal().targetCpuUtilizationPct()).isNull();
    }

    @Test
    @DisplayName("an AUTO policy with a clean recommendation still reaches the wire as not appliable")
    void theGateForcesAutoApplyFalseEvenWhenTheEngineSaysYes() {
        WorkloadPolicy auto = WorkloadPolicyDefaults.policy(WorkloadMode.AUTO);
        WorkloadSnapshot snapshot = guaranteedOverprovisioned();
        WorkloadDecision decision = engine.decide(snapshot, auto, NodeCapacity.unbounded());

        // The engine is pure: it answers "does the POLICY permit this", and here it says yes.
        assertThat(decision.autoApplyEligible())
                .as("the fixture must actually exercise the gate, not pass for another reason")
                .isTrue();

        // The wire value is engine AND gate, and the gate is false while no executor exists — so a
        // stored AUTO policy is inert rather than merely undocumented.
        assertThat(VerticalDecisionMapper.toDto(snapshot, auto, decision, GATE).autoApplyEligible())
                .isFalse();

        // Same with both flags off, which is the shipped default.
        assertThat(VerticalDecisionMapper.toDto(snapshot, auto, decision,
                new WorkloadAutoscalingProperties(false, false)).autoApplyEligible()).isFalse();
    }

    @Test
    @DisplayName("lastAction is null: nothing has ever been applied, because there is no executor")
    void lastActionIsNull() {
        WorkloadSnapshot snapshot = guaranteedOverprovisioned();
        WorkloadDecision decision = engine.decide(snapshot, policy, NodeCapacity.unbounded());

        assertThat(VerticalDecisionMapper.toDto(snapshot, policy, decision, GATE).lastAction()).isNull();
    }

    private static WorkloadSnapshot guaranteedOverprovisioned() {
        WorkloadRef ref = WorkloadRef.of("team-a", "Deployment", "overprovisioned-api");
        return new WorkloadSnapshot(ref, 2, false, false, null, null, null, List.of(
                new ContainerSnapshot("api", new ResourceEnvelope(1000, 1000, 1024 * MI, 1024 * MI),
                        ContainerUsageFixtures.usage("api", 1, 419_430L))));
    }
}
