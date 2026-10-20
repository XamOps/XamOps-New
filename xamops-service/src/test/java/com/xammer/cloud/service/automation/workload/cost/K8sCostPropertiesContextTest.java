package com.xammer.cloud.service.automation.workload.cost;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boot-level regression guard for the cost config component (FEAT-001, REQUIRED).
 *
 * <p>{@link K8sCostProperties} is a {@code @Component} Spring must instantiate through a
 * {@code @Value} constructor at startup. Its unit test calls the constructor directly, so a class
 * that compiles and passes that test can still be un-instantiable by Spring — exactly the failure
 * that crashed the context when {@code RpsSizingProperties} shipped a second unmarked constructor.
 * This binds it as a real bean through an {@link ApplicationContextRunner}, the same slice-of-context
 * approach {@code WorkloadConfigPropertiesContextTest} uses, so the failure surfaces at build time.
 */
class K8sCostPropertiesContextTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(K8sCostProperties.class);

    @Test
    @DisplayName("Spring instantiates the cost config component with its defaults")
    void bindsAsRealBeanWithDefaults() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(K8sCostProperties.class);

            K8sCostProperties props = context.getBean(K8sCostProperties.class);
            assertThat(props.enabled()).isFalse();
            assertThat(props.hoursPerMonth()).isEqualTo(730);
            assertThat(props.weightCpu()).isEqualTo(1.0);
            assertThat(props.weightMem()).isEqualTo(0.5);
            assertThat(props.onDemandTtl()).isEqualTo(Duration.ofHours(6));
            assertThat(props.spotTtl()).isEqualTo(Duration.ofMinutes(15));
            assertThat(props.transientNegativeTtl()).isEqualTo(Duration.ofSeconds(60));
            assertThat(props.spotLookback()).isEqualTo(Duration.ofMinutes(30));
            assertThat(props.maxCacheEntries()).isEqualTo(10000);
        });
    }

    @Test
    @DisplayName("Spring binds the cost keys from the environment through the real @Value constructor")
    void bindsFromProperties() {
        runner.withPropertyValues(
                K8sCostProperties.ENABLED_KEY + "=true",
                K8sCostProperties.ON_DEMAND_TTL_KEY + "=2h",
                K8sCostProperties.SPOT_TTL_KEY + "=5m",
                K8sCostProperties.HOURS_PER_MONTH_KEY + "=720",
                K8sCostProperties.WEIGHT_CPU_KEY + "=2.0",
                K8sCostProperties.WEIGHT_MEM_KEY + "=1.0"
        ).run(context -> {
            assertThat(context).hasNotFailed();

            K8sCostProperties props = context.getBean(K8sCostProperties.class);
            assertThat(props.enabled()).isTrue();
            assertThat(props.onDemandTtl()).isEqualTo(Duration.ofHours(2));
            assertThat(props.spotTtl()).isEqualTo(Duration.ofMinutes(5));
            assertThat(props.hoursPerMonth()).isEqualTo(720);
            assertThat(props.weightCpu()).isEqualTo(2.0);
            assertThat(props.weightMem()).isEqualTo(1.0);
        });
    }
}
