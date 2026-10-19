package com.xammer.cloud.service.automation.workload;

import com.xammer.cloud.service.automation.workload.rca.RcaProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boot-level regression guard for the FEAT-002/FEAT-003 config components.
 *
 * <p>Each of these is a {@code @Component} that Spring must instantiate through a {@code @Value}
 * constructor at context startup. Their unit tests call the constructors directly, so a class that
 * compiles and passes those tests can still be un-instantiable by Spring — exactly what happened
 * when {@link RpsSizingProperties} declared a second constructor without marking the production one
 * {@code @Autowired}: Spring then looked for a no-arg constructor, found none, and the whole
 * {@code xamops-service} context failed to start with {@code NoSuchMethodException: <init>()}.
 *
 * <p>Compilation and the per-class unit tests do not catch that. This test does, by binding the
 * three components as real beans through an {@link ApplicationContextRunner} — the same slice-of-
 * context approach {@code MessagingConfigurationBindingTest} uses — so the failure surfaces at
 * build time rather than on the next UAT deploy. The full {@code @SpringBootTest} that would also
 * have caught it is {@code @Disabled} because it needs PostgreSQL/Redis/Kafka.
 */
class WorkloadConfigPropertiesContextTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(RpsSizingProperties.class, ConfidenceProperties.class,
                    RcaProperties.class);

    @Test
    @DisplayName("Spring instantiates all three workload config components with their defaults")
    void componentsBindAsRealBeansWithDefaults() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();

            assertThat(context).hasSingleBean(RpsSizingProperties.class);
            assertThat(context).hasSingleBean(ConfidenceProperties.class);
            assertThat(context).hasSingleBean(RcaProperties.class);

            // The RPS bean is the one that regressed: prove its @Value defaults actually bound
            // through Spring construction, not just that a bean exists.
            RpsSizingProperties rps = context.getBean(RpsSizingProperties.class);
            assertThat(rps.enabled()).isFalse();
            assertThat(rps.hasMetric()).isFalse();
            assertThat(rps.targetUtil()).isEqualTo(0.70);
            assertThat(rps.hasCapacity()).isFalse();

            ConfidenceProperties confidence = context.getBean(ConfidenceProperties.class);
            assertThat(confidence.floor()).isEqualTo(0.5);
            assertThat(confidence.highThreshold()).isEqualTo(0.8);

            RcaProperties rca = context.getBean(RcaProperties.class);
            assertThat(rca.windowHours()).isEqualTo(24);
        });
    }

    @Test
    @DisplayName("Spring binds the RPS keys from the environment through the real @Value constructor")
    void rpsKeysBindFromProperties() {
        runner.withPropertyValues(
                RpsSizingProperties.ENABLED_KEY + "=true",
                RpsSizingProperties.METRIC_KEY + "=http_requests_total",
                RpsSizingProperties.TARGET_UTIL_KEY + "=0.80",
                RpsSizingProperties.CAPACITY_KEY + "=125.5"
        ).run(context -> {
            assertThat(context).hasNotFailed();

            RpsSizingProperties rps = context.getBean(RpsSizingProperties.class);
            assertThat(rps.enabled()).isTrue();
            assertThat(rps.metric()).isEqualTo("http_requests_total");
            assertThat(rps.hasMetric()).isTrue();
            assertThat(rps.targetUtil()).isEqualTo(0.80);
            assertThat(rps.capacityPerReplicaRps()).isEqualTo(125.5);
            assertThat(rps.hasCapacity()).isTrue();
        });
    }
}
