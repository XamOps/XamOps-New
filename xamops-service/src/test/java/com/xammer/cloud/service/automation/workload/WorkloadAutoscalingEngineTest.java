package com.xammer.cloud.service.automation.workload;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;

import static com.xammer.cloud.service.automation.workload.ReferenceWorkloads.MI;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * One case per rule, over the measured reference workloads.
 *
 * <p>Every assertion here corresponds to a failure that was <em>observed</em> on a real EKS 1.36.4
 * cluster, not to a hypothetical. The two that matter most are case 2 — the correctly-sized control
 * workload that must receive no recommendation at all — and case 11, the fixed point that makes the
 * measured {@code 1Mi → 4Mi} restart loop structurally impossible.
 */
class WorkloadAutoscalingEngineTest {

    private final WorkloadAutoscalingEngine engine =
            new WorkloadAutoscalingEngine(Clock.fixed(ReferenceWorkloads.NOW, ZoneOffset.UTC));

    private final WorkloadPolicy recommend = WorkloadPolicyDefaults.policy(WorkloadMode.RECOMMEND);

    // ============================================================ 1. floored decrease

    @Test
    @DisplayName("1: overprovisioned-api gets a large decrease floored at 25m/250Mi, never 1Mi or 0m")
    void overprovisionedApiIsFlooredNotZeroed() {
        WorkloadDecision decision = engine.decide(
                ReferenceWorkloads.overprovisionedApi(), recommend, NodeCapacity.unbounded());

        assertThat(decision.skipped()).isFalse();
        ContainerDecision api = only(decision);

        assertThat(api.recommended()).isNotNull();
        assertThat(api.recommended().cpuRequestMillis())
                .as("the Go engine recommended 1m here; VPA independently stopped at 25m")
                .isEqualTo(25);
        assertThat(api.recommended().memRequestBytes())
                .as("the Go engine recommended 1Mi here; VPA independently stopped at 250Mi")
                .isEqualTo(250 * MI);
        assertThat(api.reasons()).contains(ContainerReason.FLOOR_CLAMPED);
        assertThat(api.changeKind()).isEqualTo(ChangeKind.DECREASE);

        // Audit bug 7: requests and limits move together, so QoS cannot silently downgrade.
        assertThat(api.recommended().cpuLimitMillis()).isEqualTo(25);
        assertThat(api.recommended().memLimitBytes()).isEqualTo(250 * MI);
        assertThat(QosRules.shapeOf(api.recommended())).isEqualTo(QosRules.Shape.GUARANTEED_SHAPED);
        assertThat(api.qosClass()).isEqualTo(QosClass.GUARANTEED);

        // The reason code must not claim a direction the numbers contradict: both limits fall by
        // ~97.5% here, and the code used to be LIMIT_RAISED. The UI renders reasons as text, so a
        // direction-carrying code on a reduction told the operator the opposite of what happens.
        assertThat(api.reasons()).contains(ContainerReason.LIMIT_MOVED);
        assertThat(api.recommended().cpuLimitMillis()).isLessThan(api.current().cpuLimitMillis());
        assertThat(api.recommended().memLimitBytes()).isLessThan(api.current().memLimitBytes());
        assertThat(api.reasons().stream().map(Enum::name).toList())
                .as("no reason code may assert a limit raise while the limits are being cut")
                .doesNotContain("LIMIT_RAISED");
    }

    // ============================================================ 2. the control workload

    @Test
    @DisplayName("2: rightsized-worker — the control — gets NO recommendation at all")
    void theCorrectlySizedControlWorkloadIsLeftAlone() {
        WorkloadDecision decision = engine.decide(
                ReferenceWorkloads.rightsizedWorker(), recommend, NodeCapacity.unbounded());

        ContainerDecision worker = only(decision);

        assertThat(worker.recommended())
                .as("the Go engine changed this correctly-sized workload by -32.8%%")
                .isNull();
        assertThat(worker.changeKind()).isEqualTo(ChangeKind.NONE);
        // CPU: 80m -> 60m is -25%, which clears the 30%... no: it does not. But even a move that
        // did clear the relative band would be only -20m, far below the 100m absolute band.
        assertThat(worker.reasons()).contains(ContainerReason.WITHIN_HYSTERESIS);
        // Memory: the target is below the 250Mi floor and the current request is also below it, so
        // the engine does not raise it to reach the floor.
        assertThat(worker.reasons()).contains(ContainerReason.BELOW_FLOOR_NO_RAISE);
    }

    @Test
    @DisplayName("2b: regression — the measured -32.8% / -26m drift is suppressed by the absolute band")
    void theMeasuredDriftIsSuppressed() {
        WorkloadDecision decision = engine.decide(
                ReferenceWorkloads.rightsizedWorkerAtMeasuredDrift(), recommend, NodeCapacity.unbounded());

        ContainerDecision worker = only(decision);

        // -26m clears 30% relative (26/80 = 32.5%) and is suppressed ONLY by the 100m absolute
        // threshold. This is precisely why the band is relative AND absolute rather than either
        // alone: a relative-only band of any usable value cannot suppress this.
        assertThat(worker.recommended()).isNull();
        assertThat(worker.reasons()).contains(ContainerReason.WITHIN_HYSTERESIS);
    }

    // ============================================================ 3. throttling

    @Test
    @DisplayName("3: throttled-encoder — target from the limit, limit raised, still Burstable")
    void throttlingIsRelievedRatherThanMisread() {
        WorkloadDecision decision = engine.decide(
                ReferenceWorkloads.throttledEncoder(), recommend, NodeCapacity.unbounded());

        assertThat(decision.findings()).contains(Finding.THROTTLED);
        ContainerDecision encoder = only(decision);

        assertThat(encoder.reasons()).contains(ContainerReason.DEMAND_CENSORED_BY_LIMIT);
        assertThat(encoder.recommended().cpuRequestMillis())
                .as("50m limit x 1.25 = 63m, which is also VPA's independently computed target")
                .isEqualTo(63);
        assertThat(encoder.recommended().cpuLimitMillis())
                .as("ratio 5.0 would give 315m, capped at 4x the current 50m limit")
                .isEqualTo(200);
        // The same code as case 1, where the limits fall: one direction-neutral code, with the
        // direction readable from the numbers in the same payload.
        assertThat(encoder.reasons()).contains(ContainerReason.LIMIT_MOVED);
        assertThat(encoder.recommended().cpuLimitMillis()).isGreaterThan(encoder.current().cpuLimitMillis());
        assertThat(encoder.recommended().cpuRequestMillis())
                .isLessThanOrEqualTo(encoder.recommended().cpuLimitMillis());
        assertThat(QosRules.shapeOf(encoder.recommended())).isEqualTo(QosRules.Shape.BURSTABLE_SHAPED);

        // Memory: the current 32Mi request is below the 250Mi floor, so it is not raised.
        assertThat(encoder.recommended().memRequestBytes()).isEqualTo(32 * MI);
        assertThat(encoder.reasons()).contains(ContainerReason.BELOW_FLOOR_NO_RAISE);

        // The limit is only usable as the basis because the measurement sat ON it: p95 50m against
        // a 50m limit. 50 x 1.25 = 63m either way, so this step is backed by measurement and not
        // only by the limit's existence.
        assertThat(encoder.recommended().cpuRequestMillis())
                .as("the limit-derived target may not exceed the measured percentile plus headroom "
                        + "by more than the censoring tolerance")
                .isLessThanOrEqualTo((int) Math.round(50 * 1.25 / 0.9));
    }

    @Test
    @DisplayName("3b: the throttled path converges — feeding its own output back recommends nothing")
    void theThrottledPathConverges() {
        WorkloadDecision first = engine.decide(
                ReferenceWorkloads.throttledEncoder(), recommend, NodeCapacity.unbounded());
        assertThat(only(first).recommended().cpuRequestMillis()).isEqualTo(63);
        assertThat(only(first).recommended().cpuLimitMillis()).isEqualTo(200);

        // The engine raised the limit, so on the next cycle the limit is a number THIS ENGINE wrote.
        // Taking it as the demand basis again would walk the request 63m -> 250m -> 993m until the
        // node-capacity cap bound it -- the measured 1Mi -> 4Mi thrash loop in the other direction.
        // The measured window is unchanged at p95 50m, which is no longer anywhere near the 200m
        // limit, so the basis is the measurement again and 63m is where the request already sits.
        WorkloadDecision second = engine.decide(
                ReferenceWorkloads.throttledEncoderAfterApply(), recommend, NodeCapacity.unbounded());

        ContainerDecision encoder = only(second);
        assertThat(encoder.recommended())
                .as("a second pass over the throttled path's own output must recommend nothing")
                .isNull();
        assertThat(encoder.reasons())
                .as("the limit is not evidence of demand once usage is far below it")
                .doesNotContain(ContainerReason.DEMAND_CENSORED_BY_LIMIT);
    }

    @Test
    @DisplayName("3c: throttling alone is not censoring — a bursty container keeps the measured basis")
    void burstyThrottlingDoesNotLicenseALimitDerivedTarget() {
        // Ratio 0.25 clears the 0.2 throttling threshold, but p95 80m is 8% of the 1000m limit, so
        // usage is plainly not pinned at the ceiling. The engine used to take the limit here and
        // recommend 1250m -- a 12.5x increase for a container measured using 80m.
        WorkloadDecision decision = engine.decide(
                ReferenceWorkloads.intermittentlyThrottledApi(), recommend, NodeCapacity.unbounded());

        assertThat(decision.findings())
                .as("the throttling is real and is still reported")
                .contains(Finding.THROTTLED);

        ContainerDecision api = only(decision);
        assertThat(api.reasons()).doesNotContain(ContainerReason.DEMAND_CENSORED_BY_LIMIT);
        assertThat(api.recommended())
                .as("80m measured, 100m already requested: there is nothing to recommend, and "
                        + "certainly not 1250m")
                .isNull();
    }

    @Test
    @DisplayName("3d: once usage really does fill the raised limit, the increase is backed by measurement")
    void aCensoredTargetIsBoundedByWhatWasMeasured() {
        WorkloadDecision decision = engine.decide(
                ReferenceWorkloads.throttledEncoderSaturatingTheRaisedLimit(), recommend,
                NodeCapacity.unbounded());

        ContainerDecision encoder = only(decision);

        assertThat(encoder.reasons()).contains(ContainerReason.DEMAND_CENSORED_BY_LIMIT);
        assertThat(encoder.recommended().cpuRequestMillis())
                .as("p95 200m x 1.25 = 250m: the same step the ordinary usage-derived path gives, "
                        + "because the measurement and the limit now coincide")
                .isEqualTo(250);

        // And that step terminates too: with the request at 250m and the limit raised to 794m, the
        // same 200m measurement is no longer censored, so the series stops instead of ratcheting.
        WorkloadSnapshot afterTheIncrease = ReferenceWorkloads.workload(
                WorkloadRef.of("prod-sim", "Deployment", "throttled-encoder"), 2, List.of(
                        ReferenceWorkloads.container("encoder",
                                ReferenceWorkloads.burstable(250, encoder.recommended().cpuLimitMillis(),
                                        32 * MI, null),
                                ReferenceWorkloads.usage("encoder", 200, 20 * MI, 1.0, 0, 1.0))));

        ContainerDecision settled =
                only(engine.decide(afterTheIncrease, recommend, NodeCapacity.unbounded()));

        assertThat(settled.recommended()).isNull();
        assertThat(settled.reasons()).doesNotContain(ContainerReason.DEMAND_CENSORED_BY_LIMIT);
    }

    // ============================================================ 4. HPA coexistence

    @Test
    @DisplayName("4a: php-apache — a CPU HPA freezes the CPU request")
    void aCpuHpaFreezesTheCpuRequest() {
        // Demand that would otherwise produce a decrease, as measured: 200m -> ~144m.
        WorkloadDecision decision = engine.decide(
                ReferenceWorkloads.phpApache(115, 40 * MI), recommend, NodeCapacity.unbounded());

        assertThat(decision.findings()).contains(Finding.HPA_COEXISTENCE);
        ContainerDecision php = only(decision);

        assertThat(php.reasons()).contains(ContainerReason.HPA_CPU_MANAGED);
        assertThat(php.recommended())
                .as("the CPU request is the HPA's utilisation denominator; memory is below its floor")
                .isNull();
    }

    @Test
    @DisplayName("4b: php-apache — the freeze holds even when demand would raise CPU far past the band")
    void theHpaFreezeIsLoadBearingNotAnArtefactOfHysteresis() {
        // p95 CPU 320m -> target 400m, double the current request: an increase no band would stop.
        // Memory is raised past its floor and band so there IS a recommendation to inspect, which
        // lets the test assert the CPU request specifically rather than just "nothing happened".
        WorkloadDecision decision = engine.decide(
                ReferenceWorkloads.phpApache(320, 400 * MI), recommend, NodeCapacity.unbounded());

        ContainerDecision php = only(decision);

        assertThat(php.reasons()).contains(ContainerReason.HPA_CPU_MANAGED);
        assertThat(php.recommended()).isNotNull();
        assertThat(php.recommended().cpuRequestMillis())
                .as("frozen at the current value, not raised to 400m")
                .isEqualTo(200);
        assertThat(php.recommended().memRequestBytes())
                .as("memory stays eligible when only CPU is HPA-managed")
                .isEqualTo(500 * MI);
        assertThat(php.changeKind()).isEqualTo(ChangeKind.INCREASE);
    }

    @Test
    @DisplayName("4c: an HPA that scaled in the last 5 minutes makes the workload volatile")
    void aRecentlyScaledHpaSkipsTheWorkload() {
        WorkloadSnapshot base = ReferenceWorkloads.phpApache(115, 40 * MI);
        HpaView scaledJustNow = new HpaView("php-apache", "team-b", "Deployment", "php-apache",
                1, 5, 2, true, false, 50, ReferenceWorkloads.NOW.minus(Duration.ofMinutes(1)));
        WorkloadSnapshot volatileWorkload = new WorkloadSnapshot(base.ref(), base.replicas(), false, false,
                scaledJustNow, null, null, base.containers());

        WorkloadDecision decision = engine.decide(volatileWorkload, recommend, NodeCapacity.unbounded());

        assertThat(decision.skipReason()).isEqualTo(SkipReason.HPA_VOLATILE);
        assertThat(decision.containers()).isEmpty();
    }

    @Test
    @DisplayName("4d: an HPA matched by name prefix alone is not this workload's HPA")
    void theHpaMustMatchExactlyNotByPrefix() {
        WorkloadRef ref = WorkloadRef.of("team-b", "Deployment", "php-apache");
        HpaView canaryHpa = HpaView.cpuHpa("php-apache-canary",
                WorkloadRef.of("team-b", "Deployment", "php-apache-canary"), 50, 1, 5);
        WorkloadSnapshot snapshot = new WorkloadSnapshot(ref, 2, false, false, canaryHpa, null, null,
                List.of(ReferenceWorkloads.container("php-apache",
                        ReferenceWorkloads.burstable(200, null, 64 * MI, null),
                        ReferenceWorkloads.usage("php-apache", 320, 400 * MI))));

        WorkloadDecision decision = engine.decide(snapshot, recommend, NodeCapacity.unbounded());

        assertThat(decision.findings()).doesNotContain(Finding.HPA_COEXISTENCE);
        assertThat(only(decision).reasons()).doesNotContain(ContainerReason.HPA_CPU_MANAGED);
    }

    // ============================================================ 5. requests unset

    @Test
    @DisplayName("5: no-requests-sidecar-app — advisory only, null current, per-container values")
    void unsetRequestsAreAdvisoryAndNeverFabricated() {
        WorkloadPolicy auto = WorkloadPolicyDefaults.policy(WorkloadMode.AUTO);
        WorkloadDecision decision = engine.decide(
                ReferenceWorkloads.noRequestsSidecarApp(), auto, NodeCapacity.unbounded());

        assertThat(decision.findings()).contains(Finding.REQUESTS_UNSET);
        assertThat(decision.autoApplyEligible())
                .as("the engine is inventing a size for a container that never had one")
                .isFalse();

        ContainerDecision app = decision.containers().get(0);
        ContainerDecision sidecar = decision.containers().get(1);

        // The measured Go output reported 0m / 1Mi as the CURRENT state of a container that had
        // neither. Null is the only honest answer, and 0 is unrepresentable.
        assertThat(app.current().cpuRequestMillis()).isNull();
        assertThat(app.current().memRequestBytes()).isNull();
        assertThat(app.current().cpuLimitMillis()).isNull();
        assertThat(app.current().memLimitBytes()).isNull();
        assertThat(app.qosClass()).isEqualTo(QosClass.BEST_EFFORT);

        assertThat(app.reasons()).contains(ContainerReason.REQUESTS_UNSET);
        assertThat(app.autoApplyEligible()).isFalse();

        // Audit bug 8: one metric per Deployment name applied to every container. These differ.
        assertThat(app.recommended().cpuRequestMillis()).isEqualTo(150);
        assertThat(app.recommended().memRequestBytes()).isEqualTo(375 * MI);
        assertThat(sidecar.recommended().cpuRequestMillis())
                .as("the sidecar's own tiny usage, floored — not the app's value copied")
                .isEqualTo(25);
        assertThat(sidecar.recommended().memRequestBytes()).isEqualTo(250 * MI);
        assertThat(sidecar.recommended().cpuRequestMillis())
                .isNotEqualTo(app.recommended().cpuRequestMillis());

        // No limit is invented where there was none: that would change the QoS class.
        assertThat(app.recommended().cpuLimitMillis()).isNull();
        assertThat(app.recommended().memLimitBytes()).isNull();
    }

    // ============================================================ 6. idle

    @Test
    @DisplayName("6: idle-batch-listener — IDLE finding, and no request recommendation")
    void anIdleWorkloadGetsAFindingNotANumber() {
        WorkloadDecision decision = engine.decide(
                ReferenceWorkloads.idleBatchListener(), recommend, NodeCapacity.unbounded());

        assertThat(decision.findings()).contains(Finding.IDLE);
        ContainerDecision listener = only(decision);

        // The Go engine recommended a 0m CPU request here. No recommendation survives, and it falls
        // out of the ordinary rules: 100m -> the 25m floor is only -75m, below the 100m absolute
        // decrease band, and 128Mi is already below the 250Mi memory floor.
        assertThat(listener.recommended()).isNull();
        assertThat(listener.reasons()).contains(ContainerReason.FLOOR_CLAMPED,
                ContainerReason.WITHIN_HYSTERESIS, ContainerReason.BELOW_FLOOR_NO_RAISE);
    }

    // ============================================================ 7. single replica

    @Test
    @DisplayName("7: load-generator — a single replica is excluded")
    void singleReplicaWorkloadsAreExcluded() {
        WorkloadDecision decision = engine.decide(
                ReferenceWorkloads.loadGenerator(), recommend, NodeCapacity.unbounded());

        assertThat(decision.skipReason()).isEqualTo(SkipReason.SINGLE_REPLICA);
        assertThat(decision.containers()).isEmpty();
        assertThat(decision.autoApplyEligible()).isFalse();
    }

    // ============================================================ 8. Guaranteed, real decrease

    @Test
    @DisplayName("8: a Guaranteed workload moves requests and limits together")
    void guaranteedWorkloadsKeepTheirClass() {
        WorkloadDecision decision = engine.decide(
                ReferenceWorkloads.guaranteedGenuineDecrease(), recommend, NodeCapacity.unbounded());

        ContainerDecision svc = only(decision);

        assertThat(svc.recommended().cpuRequestMillis()).isEqualTo(800);
        assertThat(svc.recommended().cpuLimitMillis()).isEqualTo(800);
        assertThat(svc.recommended().memRequestBytes()).isEqualTo(1024 * MI);
        assertThat(svc.recommended().memLimitBytes()).isEqualTo(1024 * MI);
        assertThat(svc.qosClass()).isEqualTo(QosClass.GUARANTEED);
        assertThat(QosRules.shapeOf(svc.recommended())).isEqualTo(QosRules.Shape.GUARANTEED_SHAPED);
    }

    @Test
    @DisplayName("8b: moving only the request on a Guaranteed container is rejected at construction")
    void aQosDowngradeCannotBeConstructed() {
        ResourceEnvelope current = ReferenceWorkloads.guaranteed(2000, 2048 * MI);

        // This is literally what the measured live run wrote: requests moved, limits left alone.
        assertThatThrownBy(() -> ResourceEnvelope.recommendation(current, 800, 2000, 1024 * MI, 2048 * MI))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("QoS");
    }

    // ============================================================ 9. 250m stays 250m

    @Test
    @DisplayName("9: a 250m CPU value stays 250m and never becomes 1 core")
    void subCoreCpuValuesSurviveThePipeline() {
        // Quantity.Value() in the Go reference rounded 250m up to 1 core -- a 4x error on every
        // sub-core container. Here the value is an integer millicore count from end to end.
        assertThat(Quantities.cpuMillis("250m")).isEqualTo(250);
        assertThat(Quantities.formatCpu(250)).isEqualTo("250m");

        WorkloadRef ref = WorkloadRef.of("prod-sim", "Deployment", "quarter-core");
        WorkloadSnapshot snapshot = ReferenceWorkloads.workload(ref, 3, List.of(
                ReferenceWorkloads.container("app",
                        ReferenceWorkloads.burstable(250, 250, 300 * MI, 300 * MI),
                        ReferenceWorkloads.usage("app", 200, 240 * MI))));

        WorkloadDecision decision = engine.decide(snapshot, recommend, NodeCapacity.unbounded());
        ContainerDecision app = only(decision);

        assertThat(app.current().cpuRequestMillis()).isEqualTo(250);
        if (app.recommended() != null) {
            assertThat(app.recommended().cpuRequestMillis())
                    .as("nothing in the pipeline may round a sub-core value up to 1000m")
                    .isLessThan(1000);
        }
    }

    // ============================================================ 10. protected namespaces

    @ParameterizedTest
    @ValueSource(strings = {"kube-system", "monitoring", "amazon-cloudwatch", "vpa", "xamops-system"})
    @DisplayName("10: protected namespaces are skipped, and a policy row cannot unprotect them")
    void protectedNamespacesAreSkipped(String namespace) {
        WorkloadRef ref = WorkloadRef.of(namespace, "Deployment", "some-controller");
        WorkloadSnapshot snapshot = ReferenceWorkloads.workload(ref, 3, List.of(
                ReferenceWorkloads.container("c",
                        ReferenceWorkloads.burstable(1000, null, 1024 * MI, null),
                        ReferenceWorkloads.usage("c", 1, 419_430L))));

        // A per-workload row that enables AUTO and disables single-replica protection still cannot
        // reach a protected namespace: the set is a code constant, not policy.
        WorkloadPolicy permissive = WorkloadPolicyDefaults.policy(WorkloadMode.AUTO)
                .withProtectSingleReplica(false);

        WorkloadDecision decision = engine.decide(snapshot, permissive, NodeCapacity.unbounded());

        assertThat(decision.skipReason()).isEqualTo(SkipReason.PROTECTED_NAMESPACE);
        assertThat(decision.containers()).isEmpty();
    }

    // ============================================================ 11. convergence

    @Test
    @DisplayName("11: feeding a decision's own output back produces no recommendation (fixed point)")
    void theEngineConverges() {
        WorkloadDecision first = engine.decide(
                ReferenceWorkloads.overprovisionedApi(), recommend, NodeCapacity.unbounded());
        assertThat(only(first).recommended()).isNotNull();

        // The measured loop wrote 1Mi on one cycle and 4Mi on the next, 60 s apart, because the
        // target was computed against usage that its own previous write had changed. Hysteresis is
        // evaluated against the CURRENT request, so a second pass over the engine's own output has
        // a zero delta.
        WorkloadDecision second = engine.decide(
                ReferenceWorkloads.overprovisionedApiAfterApply(), recommend, NodeCapacity.unbounded());

        assertThat(second.containers()).hasSize(1);
        assertThat(only(second).recommended())
                .as("a second pass over the engine's own output must recommend nothing")
                .isNull();
    }

    @Test
    @DisplayName("11b: cooldown is the second, independent anti-thrash guard")
    void cooldownSuppressesASecondRecommendation() {
        WorkloadPolicy justApplied = recommend.withLastAppliedAt(
                ReferenceWorkloads.NOW.minus(Duration.ofMinutes(5)));

        WorkloadDecision decision = engine.decide(
                ReferenceWorkloads.overprovisionedApi(), justApplied, NodeCapacity.unbounded());

        assertThat(only(decision).recommended()).isNull();
        assertThat(only(decision).reasons()).contains(ContainerReason.COOLDOWN);
    }

    @Test
    @DisplayName("11c: once the cooldown has elapsed, the recommendation returns")
    void cooldownExpires() {
        WorkloadPolicy longAgo = recommend.withLastAppliedAt(
                ReferenceWorkloads.NOW.minus(Duration.ofHours(3)));

        WorkloadDecision decision = engine.decide(
                ReferenceWorkloads.overprovisionedApi(), longAgo, NodeCapacity.unbounded());

        assertThat(only(decision).recommended()).isNotNull();
        assertThat(only(decision).reasons()).doesNotContain(ContainerReason.COOLDOWN);
    }

    // ============================================================ 12. node capacity

    @Test
    @DisplayName("12: a target larger than any node is capped to 0.9 x allocatable")
    void unschedulableTargetsAreCapped() {
        WorkloadDecision decision = engine.decide(
                ReferenceWorkloads.unschedulableDemand(), recommend, NodeCapacity.t3Medium());

        assertThat(decision.findings()).contains(Finding.NODE_CAPACITY_CAPPED);
        ContainerDecision api = only(decision);

        assertThat(api.reasons()).contains(ContainerReason.NODE_CAPACITY_CAPPED);
        assertThat(api.recommended().cpuRequestMillis())
                .as("0.9 x 1930m, with no siblings to subtract")
                .isEqualTo(1737);
    }

    @Test
    @DisplayName("12b: the cap never turns a wanted increase into a decrease")
    void theCapDoesNotShrinkAWorkload() {
        // Current request already above the node budget: the honest answer is to leave it alone,
        // not to "rightsize" it down to something it never asked for.
        WorkloadRef ref = WorkloadRef.of("team-a", "Deployment", "already-huge");
        WorkloadSnapshot snapshot = ReferenceWorkloads.workload(ref, 2, List.of(
                ReferenceWorkloads.container("api",
                        ReferenceWorkloads.burstable(1800, null, 64 * MI, null),
                        ReferenceWorkloads.usage("api", 3200, 20 * MI))));

        WorkloadDecision decision = engine.decide(snapshot, recommend, NodeCapacity.t3Medium());

        assertThat(only(decision).recommended()).isNull();
        assertThat(only(decision).reasons()).contains(ContainerReason.NODE_CAPACITY_CAPPED);
    }

    // ============================================================ 13. insufficient history

    @Test
    @DisplayName("13: 30% sample coverage is not a basis for a decision")
    void insufficientHistorySkipsTheWorkload() {
        WorkloadRef ref = WorkloadRef.of("team-a", "Deployment", "barely-observed");
        WorkloadSnapshot snapshot = ReferenceWorkloads.workload(ref, 3, List.of(
                ReferenceWorkloads.container("api",
                        ReferenceWorkloads.burstable(1000, null, 1024 * MI, null),
                        ReferenceWorkloads.usage("api", 1, 419_430L, 0.0, 0, 0.3))));

        WorkloadDecision decision = engine.decide(snapshot, recommend, NodeCapacity.unbounded());

        assertThat(decision.skipReason()).isEqualTo(SkipReason.INSUFFICIENT_HISTORY);
        assertThat(decision.containers()).isEmpty();
    }

    @Test
    @DisplayName("13b: the least-covered container decides, so a sidecar nobody measured stops the workload")
    void theLeastCoveredContainerDecides() {
        WorkloadRef ref = WorkloadRef.of("team-a", "Deployment", "half-observed");
        WorkloadSnapshot snapshot = ReferenceWorkloads.workload(ref, 3, List.of(
                ReferenceWorkloads.container("api",
                        ReferenceWorkloads.burstable(1000, null, 1024 * MI, null),
                        ReferenceWorkloads.usage("api", 1, 419_430L, 0.0, 0, 1.0)),
                ReferenceWorkloads.container("sidecar", ResourceEnvelope.UNSET,
                        ReferenceWorkloads.usage("sidecar", 1, 419_430L, 0.0, 0, 0.1))));

        WorkloadDecision decision = engine.decide(snapshot, recommend, NodeCapacity.unbounded());

        assertThat(decision.skipReason()).isEqualTo(SkipReason.INSUFFICIENT_HISTORY);
    }

    // ============================================================ 14. the horizontal half

    @Test
    @DisplayName("14: the horizontal half is NOT_EVALUATED for every fixture, always")
    void theHorizontalHalfIsNeverEvaluated() {
        List<WorkloadSnapshot> everyFixture = List.of(
                ReferenceWorkloads.overprovisionedApi(),
                ReferenceWorkloads.overprovisionedApiAfterApply(),
                ReferenceWorkloads.rightsizedWorker(),
                ReferenceWorkloads.rightsizedWorkerAtMeasuredDrift(),
                ReferenceWorkloads.throttledEncoder(),
                ReferenceWorkloads.throttledEncoderAfterApply(),
                ReferenceWorkloads.throttledEncoderSaturatingTheRaisedLimit(),
                ReferenceWorkloads.intermittentlyThrottledApi(),
                ReferenceWorkloads.phpApache(115, 40 * MI),
                ReferenceWorkloads.phpApache(320, 400 * MI),
                ReferenceWorkloads.noRequestsSidecarApp(),
                ReferenceWorkloads.idleBatchListener(),
                ReferenceWorkloads.loadGenerator(),
                ReferenceWorkloads.guaranteedGenuineDecrease(),
                ReferenceWorkloads.unschedulableDemand());

        for (WorkloadSnapshot snapshot : everyFixture) {
            WorkloadDecision decision = engine.decide(snapshot, recommend, NodeCapacity.t3Medium());
            assertThat(decision.horizontal().state())
                    .as("%s", snapshot.ref().describe())
                    .isEqualTo(HorizontalDecision.State.NOT_EVALUATED);
            assertThat(decision.horizontal().minReplicas()).isNull();
            assertThat(decision.horizontal().maxReplicas()).isNull();
            assertThat(decision.horizontal().targetCpuUtilizationPct()).isNull();
        }
    }

    // ============================================================ remaining gates and invariants

    @Test
    @DisplayName("mid-rollout and locked workloads are skipped before any arithmetic")
    void volatileWorkloadsAreSkipped() {
        WorkloadSnapshot base = ReferenceWorkloads.overprovisionedApi();

        WorkloadSnapshot rollingOut = new WorkloadSnapshot(base.ref(), base.replicas(), true, false,
                null, null, null, base.containers());
        assertThat(engine.decide(rollingOut, recommend, NodeCapacity.unbounded()).skipReason())
                .isEqualTo(SkipReason.ROLLOUT_IN_PROGRESS);

        WorkloadSnapshot locked = new WorkloadSnapshot(base.ref(), base.replicas(), false, true,
                null, null, null, base.containers());
        assertThat(engine.decide(locked, recommend, NodeCapacity.unbounded()).skipReason())
                .isEqualTo(SkipReason.LOCKED);
    }

    @Test
    @DisplayName("mode OFF is the default, and it evaluates nothing")
    void policyOffEvaluatesNothing() {
        WorkloadDecision decision = engine.decide(
                ReferenceWorkloads.overprovisionedApi(), WorkloadPolicyDefaults.policy(),
                NodeCapacity.unbounded());

        assertThat(WorkloadPolicyDefaults.policy().mode()).isEqualTo(WorkloadMode.OFF);
        assertThat(decision.skipReason()).isEqualTo(SkipReason.POLICY_OFF);
        assertThat(decision.containers()).isEmpty();
    }

    @Test
    @DisplayName("OOM kills force memory to increase-only")
    void oomKillsRefuseAMemoryDecrease() {
        WorkloadRef ref = WorkloadRef.of("prod-sim", "Deployment", "oom-prone");
        WorkloadSnapshot snapshot = ReferenceWorkloads.workload(ref, 3, List.of(
                ReferenceWorkloads.container("app",
                        ReferenceWorkloads.burstable(500, null, 2048 * MI, null),
                        ReferenceWorkloads.usage("app", 400, 400 * MI, 0.0, 3, 1.0))));

        WorkloadDecision decision = engine.decide(snapshot, recommend, NodeCapacity.unbounded());

        assertThat(decision.findings()).contains(Finding.OOM_RISK);
        ContainerDecision app = only(decision);
        assertThat(app.reasons()).contains(ContainerReason.OOM_RISK);
        if (app.recommended() != null) {
            assertThat(app.recommended().memRequestBytes())
                    .as("a memory shortfall OOM-kills immediately; the decrease is refused")
                    .isEqualTo(2048 * MI);
        }
    }

    @Test
    @DisplayName("every decision carries its provenance: percentile, window, coverage and source")
    void everyDecisionStatesItsBasis() {
        WorkloadDecision decision = engine.decide(
                ReferenceWorkloads.overprovisionedApi(), recommend, NodeCapacity.unbounded());

        Provenance provenance = decision.provenance();
        assertThat(provenance.percentile()).isEqualTo(95);
        assertThat(provenance.windowHours()).isEqualTo(24);
        assertThat(provenance.sampleCoverage()).isEqualTo(1.0);
        assertThat(provenance.usageSource()).isEqualTo("harness");
        assertThat(provenance.evaluatedAt()).isEqualTo(ReferenceWorkloads.NOW);
        assertThat(provenance.policySource()).isEqualTo(WorkloadPolicy.Source.DEFAULTS);
        assertThat(provenance.windowStart()).isEqualTo(ReferenceWorkloads.WINDOW_START);
    }

    @Test
    @DisplayName("a container with no recommendation always says why")
    void silenceIsNeverAnAnswer() {
        List<WorkloadSnapshot> noChangeFixtures = List.of(
                ReferenceWorkloads.rightsizedWorker(),
                ReferenceWorkloads.rightsizedWorkerAtMeasuredDrift(),
                ReferenceWorkloads.idleBatchListener(),
                ReferenceWorkloads.overprovisionedApiAfterApply());

        for (WorkloadSnapshot snapshot : noChangeFixtures) {
            WorkloadDecision decision = engine.decide(snapshot, recommend, NodeCapacity.unbounded());
            for (ContainerDecision container : decision.containers()) {
                if (!container.hasRecommendation()) {
                    assertThat(container.reasons())
                            .as("%s/%s", snapshot.ref().describe(), container.containerName())
                            .isNotEmpty();
                }
            }
        }
    }

    @Test
    @DisplayName("a RECOMMEND-mode decision is never auto-apply eligible")
    void recommendModeNeverAutoApplies() {
        WorkloadDecision decision = engine.decide(
                ReferenceWorkloads.overprovisionedApi(), recommend, NodeCapacity.unbounded());

        assertThat(decision.autoApplyEligible()).isFalse();
    }

    private static ContainerDecision only(WorkloadDecision decision) {
        assertThat(decision.containers()).hasSize(1);
        return decision.containers().get(0);
    }
}
