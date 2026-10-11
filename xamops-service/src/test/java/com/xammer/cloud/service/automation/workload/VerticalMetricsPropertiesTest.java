package com.xammer.cloud.service.automation.workload;

import com.xammer.cloud.service.automation.workload.usage.VerticalMetricsProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The metrics-source configuration and the two no-row defaults: safe when absent, declared exactly
 * once in every profile.
 *
 * <p>The parity half reads the five {@code application*.properties} files from disk, for the same
 * reason {@link WorkloadAutoscalingPropertiesTest} does: the failure being guarded against is
 * somebody adding or editing a profile and leaving one of these keys pointing somewhere else — or
 * declaring it twice with the second one silently winning. A binding test cannot see that.
 */
class VerticalMetricsPropertiesTest {

    private static final List<String> PROFILES = List.of(
            "application.properties",
            "application-local.properties",
            "application-uat.properties",
            "application-prod.properties",
            "application-preprod.properties");

    /** The value every profile is expected to declare, per key. */
    private static final List<String> EXPECTED_VALUES = List.of(
            "monitoring", "prometheus-server", "9090", "20", "120", "24", "OFF");

    @Test
    @DisplayName("absent properties bind to the safe defaults, so omitting configuration is harmless")
    void absentPropertiesBindSafely() {
        VerticalMetricsProperties properties = defaults();

        assertThat(properties.metricsNamespace()).isEqualTo("monitoring");
        assertThat(properties.metricsService()).isEqualTo("prometheus-server");
        assertThat(properties.metricsPort()).isEqualTo(9090);
        assertThat(properties.queryTimeoutSeconds()).isEqualTo(20);
        assertThat(properties.ignoreContainerStartSeconds()).isEqualTo(120);
        assertThat(properties.defaultWindowHours()).isEqualTo(WorkloadPolicyDefaults.WINDOW_HOURS);
        assertThat(properties.defaultMode())
                .as("the default mode must be the one that does nothing")
                .isEqualTo(WorkloadMode.OFF);
    }

    @Test
    @DisplayName("an unrecognised default-mode is OFF, not an exception and not AUTO")
    void anUnknownModeIsOff() {
        // A typo in a properties file must not take the read endpoint down, and the safe value is
        // the one that cannot change a cluster.
        assertThat(properties("monitoring", "prometheus-server", 9090, 20, 120, 24, "TURBO").defaultMode())
                .isEqualTo(WorkloadMode.OFF);
        assertThat(properties("monitoring", "prometheus-server", 9090, 20, 120, 24, "  ").defaultMode())
                .isEqualTo(WorkloadMode.OFF);
        assertThat(properties("monitoring", "prometheus-server", 9090, 20, 120, 24, "recommend").defaultMode())
                .as("case and whitespace are tolerated; the value itself is not invented")
                .isEqualTo(WorkloadMode.RECOMMEND);
    }

    @Test
    @DisplayName("a blank or non-positive value falls back rather than producing an unusable path")
    void nonsenseValuesFallBack() {
        VerticalMetricsProperties nonsense = properties("  ", "", 0, -5, -1, 0, "OFF");

        assertThat(nonsense.metricsNamespace()).isEqualTo("monitoring");
        assertThat(nonsense.metricsService()).isEqualTo("prometheus-server");
        assertThat(nonsense.metricsPort()).isEqualTo(9090);
        assertThat(nonsense.queryTimeoutSeconds()).isEqualTo(20);
        assertThat(nonsense.ignoreContainerStartSeconds()).isEqualTo(120);
        assertThat(nonsense.defaultWindowHours()).isEqualTo(24);
    }

    @Test
    @DisplayName("zero means 'do not exclude young containers', which is a real choice, not nonsense")
    void zeroIgnoreWindowIsHonoured() {
        assertThat(properties("monitoring", "prometheus-server", 9090, 20, 0, 24, "OFF")
                .ignoreContainerStartSeconds()).isZero();
    }

    @Test
    @DisplayName("every profile declares every key exactly once, with the safe value")
    void everyProfileDeclaresEveryKeyOnce() {
        assertThat(VerticalMetricsProperties.KEYS).hasSameSizeAs(EXPECTED_VALUES);

        for (String profile : PROFILES) {
            String contents = read(profile);
            for (int i = 0; i < VerticalMetricsProperties.KEYS.size(); i++) {
                String key = VerticalMetricsProperties.KEYS.get(i);
                assertThat(activeValues(contents, key))
                        .as("%s must declare %s exactly once", profile, key)
                        .containsExactly(EXPECTED_VALUES.get(i));
            }
        }
    }

    @Test
    @DisplayName("the key names sit under the engine's own prefix, next to the two safety flags")
    void theKeysAreNamedConsistently() {
        for (String key : VerticalMetricsProperties.KEYS) {
            assertThat(key).startsWith("xamops.automation.vertical.");
        }
        assertThat(VerticalMetricsProperties.DEFAULT_WINDOW_HOURS_KEY)
                .isEqualTo("xamops.automation.vertical.default-window-hours");
        assertThat(VerticalMetricsProperties.DEFAULT_MODE_KEY)
                .isEqualTo("xamops.automation.vertical.default-mode");
    }

    static VerticalMetricsProperties defaults() {
        return properties("monitoring", "prometheus-server", 9090, 20, 120, 24, "OFF");
    }

    static VerticalMetricsProperties properties(String namespace, String service, int port,
                                                int timeoutSeconds, int ignoreStartSeconds,
                                                int defaultWindowHours, String defaultMode) {
        return new VerticalMetricsProperties(namespace, service, port, timeoutSeconds,
                ignoreStartSeconds, defaultWindowHours, defaultMode);
    }

    /** The values of every uncommented declaration of {@code key}. */
    private static List<String> activeValues(String contents, String key) {
        return contents.lines()
                .map(String::strip)
                .filter(line -> !line.startsWith("#"))
                .filter(line -> line.startsWith(key + "="))
                .map(line -> line.substring(key.length() + 1).strip())
                .toList();
    }

    private static String read(String profile) {
        Path path = Path.of("src/main/resources", profile);
        assertThat(path).as("%s must exist", profile).exists();
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
