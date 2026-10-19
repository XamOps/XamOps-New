package com.xammer.cloud.service.automation.workload;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The confidence band-edge binding, asserted directly on the constructor.
 *
 * <p>Deliberately does <strong>not</strong> read any {@code application*.properties} file: these two
 * keys are bound in a standalone {@code @Component} precisely so the pinned profile-parity test
 * ({@code VerticalMetricsPropertiesTest}) stays untouched. This test pins the defaults and the
 * defensive fallbacks only.
 */
class ConfidencePropertiesTest {

    @Test
    @DisplayName("the defaults are floor 0.5 and high-threshold 0.8")
    void defaultsBind() {
        ConfidenceProperties props = new ConfidenceProperties(0.5, 0.8);
        assertThat(props.floor()).isEqualTo(0.5);
        assertThat(props.highThreshold()).isEqualTo(0.8);
    }

    @Test
    @DisplayName("an out-of-range or non-finite value falls back to the safe default")
    void outOfRangeFallsBack() {
        assertThat(new ConfidenceProperties(-0.3, 0.8).floor()).isEqualTo(0.5);
        assertThat(new ConfidenceProperties(0.5, 1.4).highThreshold()).isEqualTo(0.8);
        assertThat(new ConfidenceProperties(Double.NaN, Double.POSITIVE_INFINITY).floor()).isEqualTo(0.5);
        assertThat(new ConfidenceProperties(Double.NaN, Double.POSITIVE_INFINITY).highThreshold())
                .isEqualTo(0.8);
    }

    @Test
    @DisplayName("a high-threshold below the floor is corrected up to the floor (non-inversion guard)")
    void highThresholdNeverInvertsBelowFloor() {
        ConfidenceProperties props = new ConfidenceProperties(0.6, 0.4);
        assertThat(props.floor()).isEqualTo(0.6);
        assertThat(props.highThreshold())
                .as("the HIGH cut can never sit below the LOW cut")
                .isEqualTo(0.6);
    }

    @Test
    @DisplayName("valid non-default values bind as given")
    void validValuesBind() {
        ConfidenceProperties props = new ConfidenceProperties(0.4, 0.7);
        assertThat(props.floor()).isEqualTo(0.4);
        assertThat(props.highThreshold()).isEqualTo(0.7);
    }
}
