package com.xammer.cloud.service.automation.workload;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The F3 configuration component. It binds its own keys with {@code :}-defaults and does NOT read
 * the {@code application*.properties} profiles, so the pinned {@code HorizontalAutoscalingPropertiesTest}
 * parity is untouched. The defaults encode the honest-unavailable posture: off, no metric, no
 * capacity.
 */
class RpsSizingPropertiesTest {

    @Test
    @DisplayName("the defaults bind to the honest-unavailable posture: off, no metric, no capacity")
    void defaultsAreTheHonestUnavailablePosture() {
        // The @Value placeholder defaults, exercised through the production constructor.
        RpsSizingProperties props = new RpsSizingProperties(false, "", 0.70, "");

        assertThat(props.enabled()).isFalse();
        assertThat(props.metric()).isEmpty();
        assertThat(props.hasMetric()).isFalse();
        assertThat(props.targetUtil()).isEqualTo(0.70);
        assertThat(props.capacityPerReplicaRps()).isNull();
        assertThat(props.hasCapacity()).isFalse();
    }

    @Test
    @DisplayName("a configured metric and capacity bind, and a blank is trimmed to unset")
    void configuredValuesBind() {
        RpsSizingProperties props = new RpsSizingProperties(true, "  http_requests_total  ", 0.80,
                "125.5");

        assertThat(props.enabled()).isTrue();
        assertThat(props.metric()).isEqualTo("http_requests_total");
        assertThat(props.hasMetric()).isTrue();
        assertThat(props.targetUtil()).isEqualTo(0.80);
        assertThat(props.capacityPerReplicaRps()).isEqualTo(125.5);
        assertThat(props.hasCapacity()).isTrue();
    }

    @Test
    @DisplayName("a non-positive or unparseable capacity is treated as unset, never a fabricated number")
    void badCapacityIsUnset() {
        assertThat(new RpsSizingProperties(true, "m", 0.70, "0").capacityPerReplicaRps()).isNull();
        assertThat(new RpsSizingProperties(true, "m", 0.70, "-10").capacityPerReplicaRps()).isNull();
        assertThat(new RpsSizingProperties(true, "m", 0.70, "notanumber").capacityPerReplicaRps())
                .isNull();
        assertThat(new RpsSizingProperties(true, "m", 0.70, "   ").capacityPerReplicaRps()).isNull();
    }

    @Test
    @DisplayName("a non-positive target utilisation falls back to the 0.70 default")
    void badTargetUtilFallsBack() {
        assertThat(new RpsSizingProperties(true, "m", 0, "50").targetUtil()).isEqualTo(0.70);
        assertThat(new RpsSizingProperties(true, "m", -0.5, "50").targetUtil()).isEqualTo(0.70);
        assertThat(new RpsSizingProperties(true, "m", Double.NaN, "50").targetUtil()).isEqualTo(0.70);
    }
}
