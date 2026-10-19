package com.xammer.cloud.service.automation.workload;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pure RPS-based replica-sizing calculation.
 *
 * <p>The properties that matter are the ones the prior design got wrong (H1) and finding 1 pinned:
 * capacity is a real input so two different throughputs give two different counts; the bounds are a
 * total function (both present clamps, either null is unbounded, never a fabricated 0/MAX_VALUE);
 * and empty is reserved for the genuine "cannot size" cases only.
 */
class RpsReplicaSizingTest {

    @Test
    @DisplayName("ceil((rps / capacity) / targetUtil) against a hand-computed literal")
    void computesTheCeilOfTheQuotient() {
        // 1200 / 50 / 0.70 = 34.2857..., ceil = 35
        assertThat(RpsReplicaSizing.size(1200, 50, 0.70, null, null)).hasValue(35);
        // 700 / 100 / 0.70 = 10.0 exactly, ceil = 10
        assertThat(RpsReplicaSizing.size(700, 100, 0.70, null, null)).hasValue(10);
    }

    @Test
    @DisplayName("two different observed RPS give two different counts for a fixed capacity (H1 guard)")
    void throughputActuallyDrivesTheCount() {
        OptionalInt low = RpsReplicaSizing.size(600, 50, 0.70, null, null);
        OptionalInt high = RpsReplicaSizing.size(1800, 50, 0.70, null, null);

        assertThat(low).isNotEmpty();
        assertThat(high).isNotEmpty();
        assertThat(high.getAsInt())
                .as("if capacity were self-derived from rps, the two counts would be equal (the H1 bug)")
                .isGreaterThan(low.getAsInt());
    }

    @Test
    @DisplayName("both bounds present clamps to [min,max]")
    void bothBoundsClamp() {
        // raw 35 clamped to [2,10] -> 10
        assertThat(RpsReplicaSizing.size(1200, 50, 0.70, 2, 10)).hasValue(10);
        // raw 3 clamped up to the minimum 5 -> 5
        assertThat(RpsReplicaSizing.size(100, 50, 0.70, 5, 10)).hasValue(5);
        // raw inside [2,10] stays as computed: 100/50 = 2.0; /0.70 = 2.857...; ceil = 3
        assertThat(RpsReplicaSizing.size(100, 50, 0.70, 2, 10)).hasValue(3);
    }

    @Test
    @DisplayName("a null minimum returns the unbounded count, never empty and never a fabricated 0")
    void minNullIsUnbounded() {
        assertThat(RpsReplicaSizing.size(1200, 50, 0.70, null, 10))
                .as("either bound null is the total, defined unbounded branch")
                .hasValue(35);
    }

    @Test
    @DisplayName("a null maximum returns the unbounded count, never empty and never MAX_VALUE")
    void maxNullIsUnbounded() {
        assertThat(RpsReplicaSizing.size(1200, 50, 0.70, 2, null)).hasValue(35);
    }

    @Test
    @DisplayName("empty only for a non-positive capacity, a non-positive target, or a non-finite input")
    void emptyOnlyForTheTrueCannotSizeCases() {
        assertThat(RpsReplicaSizing.size(1200, 0, 0.70, null, null)).isEmpty();
        assertThat(RpsReplicaSizing.size(1200, -5, 0.70, null, null)).isEmpty();
        assertThat(RpsReplicaSizing.size(1200, 50, 0, null, null)).isEmpty();
        assertThat(RpsReplicaSizing.size(1200, 50, -0.1, null, null)).isEmpty();
        assertThat(RpsReplicaSizing.size(Double.NaN, 50, 0.70, null, null)).isEmpty();
        assertThat(RpsReplicaSizing.size(1200, Double.POSITIVE_INFINITY, 0.70, null, null)).isEmpty();
        assertThat(RpsReplicaSizing.size(1200, 50, Double.NaN, null, null)).isEmpty();
    }

    @Test
    @DisplayName("a zero observed rate is a defined zero count, not an empty result")
    void zeroThroughputIsZeroReplicas() {
        assertThat(RpsReplicaSizing.size(0, 50, 0.70, null, null)).hasValue(0);
    }

    @Test
    @DisplayName("bounded() is true only when both bounds are non-null")
    void boundedIsBothPresent() {
        assertThat(RpsReplicaSizing.bounded(2, 10)).isTrue();
        assertThat(RpsReplicaSizing.bounded(null, 10)).isFalse();
        assertThat(RpsReplicaSizing.bounded(2, null)).isFalse();
        assertThat(RpsReplicaSizing.bounded(null, null)).isFalse();
    }
}
