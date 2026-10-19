package com.xammer.cloud.service.automation.workload.rca;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The RCA config binding, asserted directly on the constructor.
 *
 * <p>Deliberately does <strong>not</strong> read any {@code application*.properties} file: these
 * keys bind in a standalone {@code @Component} precisely so the pinned profile-parity test
 * ({@code VerticalMetricsPropertiesTest}) stays untouched.
 */
class RcaPropertiesTest {

    @Test
    @DisplayName("the defaults bind: window 24, top-K 10, thresholds 2/3/2/2/3")
    void defaultsBind() {
        RcaProperties props = new RcaProperties(24, 10, 2, 3, 2, 2, 3);
        RcaConfig config = props.toConfig();
        assertThat(props.windowHours()).isEqualTo(24);
        assertThat(config.windowHours()).isEqualTo(24);
        assertThat(config.topK()).isEqualTo(10);
        assertThat(config.repeatedFailedThreshold()).isEqualTo(2);
        assertThat(config.repeatedRefusedThreshold()).isEqualTo(3);
        assertThat(config.partialApplyChurnThreshold()).isEqualTo(2);
        assertThat(config.expiryChurnThreshold()).isEqualTo(2);
        assertThat(config.flipFlopThreshold()).isEqualTo(3);
    }

    @Test
    @DisplayName("a non-positive value falls back to the safe default")
    void nonPositiveFallsBack() {
        RcaProperties props = new RcaProperties(0, -5, 0, -1, 0, 0, 0);
        RcaConfig config = props.toConfig();
        assertThat(config.windowHours()).isEqualTo(RcaConfig.DEFAULT_WINDOW_HOURS);
        assertThat(config.topK()).isEqualTo(RcaConfig.DEFAULT_TOP_K);
        assertThat(config.repeatedFailedThreshold()).isEqualTo(RcaConfig.DEFAULT_FAILED_THRESHOLD);
        assertThat(config.repeatedRefusedThreshold()).isEqualTo(RcaConfig.DEFAULT_REFUSED_THRESHOLD);
        assertThat(config.flipFlopThreshold()).isEqualTo(RcaConfig.DEFAULT_FLIP_FLOP_THRESHOLD);
    }

    @Test
    @DisplayName("valid non-default values bind as given")
    void validValuesBind() {
        RcaProperties props = new RcaProperties(48, 5, 4, 5, 3, 3, 2);
        RcaConfig config = props.toConfig();
        assertThat(config.windowHours()).isEqualTo(48);
        assertThat(config.topK()).isEqualTo(5);
        assertThat(config.repeatedFailedThreshold()).isEqualTo(4);
        assertThat(config.flipFlopThreshold()).isEqualTo(2);
    }
}
