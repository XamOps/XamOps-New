package com.xammer.cloud.service.automation.workload;

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
 * The auto-apply loop's master switch defaults off, the other three keys bind to their defaults when
 * absent, and <strong>uat is the only profile where the switch is true</strong>.
 *
 * <p>The parity half reads the five {@code application*.properties} files from disk, for the same
 * reason {@link WorkloadAutoscalingPropertiesTest} and {@code VerticalMetricsPropertiesTest} do: the
 * failure being guarded against is somebody adding or editing a profile and leaving the loop enabled
 * in it — or declaring a key twice with the second one silently winning. A binding test cannot see
 * that.
 */
class AutoApplyPropertiesTest {

    /** The one profile where the loop's kill switch is on. */
    private static final String ENABLED_PROFILE = "application-uat.properties";

    private static final List<String> PROFILES = List.of(
            "application.properties",
            "application-local.properties",
            ENABLED_PROFILE,
            "application-prod.properties",
            "application-preprod.properties");

    /** Per key, the value every profile except UAT is expected to declare. */
    private static final List<String> EXPECTED_VALUES = List.of("false", "15", "15", "3");

    /** UAT differs only in the master switch; the cadence/safety values are the same everywhere. */
    private static final List<String> UAT_OVERRIDES = List.of("true", "15", "15", "3");

    @Test
    @DisplayName("an absent property binds to the safe default, so omitting configuration keeps the loop off")
    void absentPropertiesBindSafely() {
        AutoApplyProperties properties = new AutoApplyProperties(false, 15, 15, 3);

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.getIntervalMinutes()).isEqualTo(15);
        assertThat(properties.getClusterCooldownMinutes()).isEqualTo(15);
        assertThat(properties.getCircuitBreakerThreshold()).isEqualTo(3);
    }

    @Test
    @DisplayName("every profile declares every auto-apply key exactly once, and only uat enables the loop")
    void everyProfileDeclaresEveryKeyOnce() {
        assertThat(AutoApplyProperties.KEYS).hasSameSizeAs(EXPECTED_VALUES);
        assertThat(UAT_OVERRIDES).hasSameSizeAs(EXPECTED_VALUES);

        for (String profile : PROFILES) {
            String contents = read(profile);
            List<String> expected = ENABLED_PROFILE.equals(profile) ? UAT_OVERRIDES : EXPECTED_VALUES;
            for (int i = 0; i < AutoApplyProperties.KEYS.size(); i++) {
                String key = AutoApplyProperties.KEYS.get(i);
                assertThat(activeValues(contents, key))
                        .as("%s must declare %s exactly once", profile, key)
                        .containsExactly(expected.get(i));
            }
        }
    }

    @Test
    @DisplayName("no profile other than uat may turn the auto-apply loop on")
    void onlyUatEnablesTheLoop() {
        List<String> enabled = PROFILES.stream()
                .filter(profile -> activeValues(read(profile), AutoApplyProperties.ENABLED_KEY)
                        .contains("true"))
                .toList();

        assertThat(enabled)
                .as("the auto-apply loop may only be on in uat")
                .containsExactly(ENABLED_PROFILE);
    }

    @Test
    @DisplayName("the key names sit under the engine's own prefix, next to the two safety flags")
    void theKeysAreNamedConsistently() {
        for (String key : AutoApplyProperties.KEYS) {
            assertThat(key).startsWith("xamops.automation.vertical.auto-apply.");
        }
        assertThat(AutoApplyProperties.ENABLED_KEY)
                .isEqualTo("xamops.automation.vertical.auto-apply.enabled");
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
