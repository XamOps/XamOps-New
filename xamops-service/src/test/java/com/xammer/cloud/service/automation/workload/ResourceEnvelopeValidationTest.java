package com.xammer.cloud.service.automation.workload;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * An invalid recommendation must not be able to exist as a value, let alone be served or applied.
 * These are the construction-time rules that make audit bugs 4 and 7 unrepresentable rather than
 * merely avoided.
 */
class ResourceEnvelopeValidationTest {

    private static final long MI = 1024L * 1024L;

    private static final ResourceEnvelope GUARANTEED = new ResourceEnvelope(1000, 1000, 1024 * MI, 1024 * MI);
    private static final ResourceEnvelope BURSTABLE = new ResourceEnvelope(100, 200, 64 * MI, null);

    @Test
    @DisplayName("null means unset and is distinct from zero")
    void unsetIsNotZero() {
        assertThat(ResourceEnvelope.UNSET.cpuRequestMillis()).isNull();
        assertThat(ResourceEnvelope.UNSET.anyRequestUnset()).isTrue();
        assertThat(ResourceEnvelope.UNSET.hasCpuRequest()).isFalse();

        // The measured Go output reported "0m" and "1Mi" as the current state of a container that
        // had neither. An envelope that reports 0 is reporting a request of zero, which is a
        // different and equally wrong claim.
        ResourceEnvelope zeroed = new ResourceEnvelope(0, null, 0L, null);
        assertThat(zeroed.anyRequestUnset()).isFalse();
        assertThat(zeroed.cpuRequestMillis()).isZero();
    }

    @Test
    @DisplayName("a zero or negative recommended request is rejected (bug 4)")
    void nonPositiveRecommendedRequestsAreRejected() {
        assertThatThrownBy(() -> ResourceEnvelope.recommendation(BURSTABLE, 0, 200, 64 * MI, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be positive");

        assertThatThrownBy(() -> ResourceEnvelope.recommendation(BURSTABLE, 100, 200, 0L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be positive");

        assertThatThrownBy(() -> new ResourceEnvelope(-1, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
    }

    @Test
    @DisplayName("a recommendation with no request at all is rejected")
    void anEmptyRecommendationIsRejected() {
        assertThatThrownBy(() -> ResourceEnvelope.recommendation(BURSTABLE, null, 200, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a recommendation");
    }

    @Test
    @DisplayName("a request above its own limit is rejected: in-place resize would refuse it (bug 7)")
    void requestAboveLimitIsRejected() {
        assertThatThrownBy(() -> ResourceEnvelope.recommendation(BURSTABLE, 500, 200, 64 * MI, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds its limit");

        assertThatThrownBy(() -> ResourceEnvelope.recommendation(
                new ResourceEnvelope(100, 200, 64 * MI, 128 * MI), 100, 200, 256 * MI, 128 * MI))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds its limit");
    }

    @Test
    @DisplayName("a Guaranteed to Burstable downgrade is rejected (bug 7, the measured case)")
    void qosDowngradeIsRejected() {
        // This is exactly what the live run wrote: requests moved to 1m/4Mi, limits left at 1 CPU/1Gi.
        assertThatThrownBy(() -> ResourceEnvelope.recommendation(GUARANTEED, 1, 1000, 4 * MI, 1024 * MI))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("QoS");
    }

    @Test
    @DisplayName("a Burstable to Guaranteed upgrade is rejected too: the class is not ours to change")
    void qosUpgradeIsAlsoRejected() {
        assertThatThrownBy(() -> ResourceEnvelope.recommendation(BURSTABLE, 200, 200, 64 * MI, 64 * MI))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("QoS");
    }

    @Test
    @DisplayName("naming a size for a container that had none is the one allowed shape transition")
    void unsetMayBecomeSet() {
        ResourceEnvelope advisory =
                ResourceEnvelope.recommendation(ResourceEnvelope.UNSET, 150, null, 375 * MI, null);

        assertThat(advisory.cpuRequestMillis()).isEqualTo(150);
        assertThat(QosRules.shapeOf(ResourceEnvelope.UNSET)).isEqualTo(QosRules.Shape.UNSET);
        assertThat(QosRules.preservesShape(ResourceEnvelope.UNSET, advisory)).isTrue();
    }

    @Test
    @DisplayName("a Guaranteed recommendation that moves requests and limits together is accepted")
    void guaranteedMovesTogether() {
        ResourceEnvelope moved =
                ResourceEnvelope.recommendation(GUARANTEED, 25, 25, 250 * MI, 250 * MI);

        assertThat(QosRules.shapeOf(moved)).isEqualTo(QosRules.Shape.GUARANTEED_SHAPED);
    }

    @Test
    @DisplayName("pod QoS class follows Kubernetes' own rule over every container")
    void podClassFollowsEveryContainer() {
        assertThat(QosRules.podClass(List.of(GUARANTEED))).isEqualTo(QosClass.GUARANTEED);
        assertThat(QosRules.podClass(List.of(GUARANTEED, BURSTABLE))).isEqualTo(QosClass.BURSTABLE);
        assertThat(QosRules.podClass(List.of(ResourceEnvelope.UNSET))).isEqualTo(QosClass.BEST_EFFORT);
        assertThat(QosRules.podClass(List.of(ResourceEnvelope.UNSET, BURSTABLE))).isEqualTo(QosClass.BURSTABLE);
        assertThat(QosClass.GUARANTEED.wireName()).isEqualTo("Guaranteed");
        assertThat(QosClass.BEST_EFFORT.wireName()).isEqualTo("BestEffort");
    }

    @Test
    @DisplayName("a Burstable limit preserves its ratio, bounded by the max-increase factor")
    void burstableLimitsPreserveTheirRatio() {
        // The measured throttled-encoder: request 10m, limit 50m, ratio 5.0, new request 63m.
        Long limit = QosRules.limitFor(QosRules.Shape.BURSTABLE_SHAPED, 10L, 50L, 63L, 4.0);
        assertThat(limit).isEqualTo(200L);   // min(63 x 5, 50 x 4)

        // A limit is never lowered below the recommended request.
        assertThat(QosRules.limitFor(QosRules.Shape.BURSTABLE_SHAPED, 1000L, 1000L, 25L, 4.0))
                .isGreaterThanOrEqualTo(25L);

        // No limit today means no limit invented: that would change the class and cap a workload
        // the operator chose to leave uncapped.
        assertThat(QosRules.limitFor(QosRules.Shape.BURSTABLE_SHAPED, 100L, null, 150L, 4.0)).isNull();

        // Guaranteed: the limit always equals the request.
        assertThat(QosRules.limitFor(QosRules.Shape.GUARANTEED_SHAPED, 1000L, 1000L, 25L, 4.0))
                .isEqualTo(25L);
    }

    @Test
    @DisplayName("the hysteresis band needs both thresholds cleared")
    void hysteresisNeedsBoth() {
        HysteresisBand cpuDecrease = WorkloadPolicyDefaults.CPU_DECREASE;   // 30% AND 100m

        // The measured false positive: 80m -> 54m is 32.5% but only 26m.
        assertThat(cpuDecrease.clears(80, 54)).isFalse();
        // A real decrease on a large container: 1000m -> 600m is 40% and 400m.
        assertThat(cpuDecrease.clears(1000, 600)).isTrue();
        // Large absolute but small relative: 10000m -> 9800m is 200m but only 2%.
        assertThat(cpuDecrease.clears(10000, 9800)).isFalse();
        // Exactly on both boundaries counts as clearing.
        assertThat(cpuDecrease.clears(1000, 700)).isTrue();

        HysteresisBand cpuIncrease = WorkloadPolicyDefaults.CPU_INCREASE;   // 15% AND 25m
        // The measured throttled encoder: 10m -> 63m.
        assertThat(cpuIncrease.clears(10, 63)).isTrue();
        // A 24m increase is below the absolute band however large the percentage.
        assertThat(cpuIncrease.clears(10, 34)).isFalse();
    }
}
