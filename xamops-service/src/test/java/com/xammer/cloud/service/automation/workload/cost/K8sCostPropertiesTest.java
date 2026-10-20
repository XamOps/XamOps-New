package com.xammer.cloud.service.automation.workload.cost;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cost config bean's defaults and bad-value fallbacks, exercised directly (no Spring).
 */
class K8sCostPropertiesTest {

    @Test
    @DisplayName("the @Value production constructor reads the documented defaults")
    void defaults() {
        K8sCostProperties props = new K8sCostProperties(false, "6h", "15m", "60s", "30m", 730,
                1.0, 0.5, 10000);

        assertThat(props.enabled()).isFalse();
        assertThat(props.onDemandTtl()).isEqualTo(Duration.ofHours(6));
        assertThat(props.spotTtl()).isEqualTo(Duration.ofMinutes(15));
        assertThat(props.transientNegativeTtl()).isEqualTo(Duration.ofSeconds(60));
        assertThat(props.spotLookback()).isEqualTo(Duration.ofMinutes(30));
        assertThat(props.hoursPerMonth()).isEqualTo(730);
        assertThat(props.weightCpu()).isEqualTo(1.0);
        assertThat(props.weightMem()).isEqualTo(0.5);
        assertThat(props.maxCacheEntries()).isEqualTo(10000);
    }

    @Test
    @DisplayName("non-positive / non-finite numerics fall back rather than divide by zero")
    void badValuesFallBack() {
        K8sCostProperties props = new K8sCostProperties(true, "-5s", "0s", "-1s", "garbage", 0.0,
                0.0, Double.NaN, 0);

        assertThat(props.enabled()).isTrue();
        assertThat(props.onDemandTtl()).isEqualTo(K8sCostProperties.DEFAULT_ON_DEMAND_TTL);
        assertThat(props.spotTtl()).isEqualTo(K8sCostProperties.DEFAULT_SPOT_TTL);
        assertThat(props.transientNegativeTtl()).isEqualTo(K8sCostProperties.DEFAULT_TRANSIENT_NEGATIVE_TTL);
        assertThat(props.spotLookback()).isEqualTo(K8sCostProperties.DEFAULT_SPOT_LOOKBACK);
        assertThat(props.hoursPerMonth()).isEqualTo(K8sCostProperties.DEFAULT_HOURS_PER_MONTH);
        assertThat(props.weightCpu()).isEqualTo(K8sCostProperties.DEFAULT_WEIGHT_CPU);
        assertThat(props.weightMem()).isEqualTo(K8sCostProperties.DEFAULT_WEIGHT_MEM);
        assertThat(props.maxCacheEntries()).isEqualTo(K8sCostProperties.DEFAULT_MAX_CACHE_ENTRIES);
    }

    @Test
    @DisplayName("the of(...) factory builds a disabled instance without Spring")
    void disabledFactory() {
        K8sCostProperties props = K8sCostProperties.disabled();
        assertThat(props.enabled()).isFalse();
        assertThat(props.hoursPerMonth()).isEqualTo(730);
    }
}
