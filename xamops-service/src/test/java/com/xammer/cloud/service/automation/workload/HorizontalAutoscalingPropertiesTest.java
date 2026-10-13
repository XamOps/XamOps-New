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
 * The HPA flags default to off, the apply path needs three things rather than two, and
 * <strong>uat is the only profile where the horizontal master switch and the D3b sub-flag are
 * true</strong>.
 *
 * <p>The parity half reads the five {@code application*.properties} files from disk — the same
 * guard {@code WorkloadAutoscalingPropertiesTest} applies to the vertical flags — because the
 * failure mode is somebody adding or editing a profile and leaving the HPA apply path enabled in it.
 */
class HorizontalAutoscalingPropertiesTest {

    private static final String ENABLED_PROFILE = "application-uat.properties";

    private static final List<String> PROFILES = List.of(
            "application.properties",
            "application-local.properties",
            ENABLED_PROFILE,
            "application-prod.properties",
            "application-preprod.properties");

    @Test
    @DisplayName("an absent property binds to false, so omitting configuration cannot turn this on")
    void absentPropertiesAreOff() {
        HorizontalAutoscalingProperties properties = new HorizontalAutoscalingProperties(false, false);

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.isReady()).isFalse();
        assertThat(properties.isTargetRetuneEnabled()).isFalse();
    }

    @Test
    @DisplayName("the apply path needs both flags AND an executor: any one missing makes it false")
    void applyNeedsBothFlagsAndAnExecutor() {
        assertThat(new HorizontalAutoscalingProperties(false, false, true).isApplyPermitted())
                .as("master switch off").isFalse();
        assertThat(new HorizontalAutoscalingProperties(true, false, true).isApplyPermitted())
                .as("readiness off: decisions are shown long before an apply is").isFalse();
        assertThat(new HorizontalAutoscalingProperties(false, true, true).isApplyPermitted())
                .as("ready but not enabled").isFalse();
        assertThat(new HorizontalAutoscalingProperties(true, true, false).isApplyPermitted())
                .as("both flags on but no executor: nothing could carry the apply").isFalse();

        HorizontalAutoscalingProperties permitted = new HorizontalAutoscalingProperties(true, true, true);
        assertThat(permitted.isEnabled()).isTrue();
        assertThat(permitted.isReady()).isTrue();
        assertThat(permitted.isExecutorPresent()).isTrue();
        assertThat(permitted.isApplyPermitted()).isTrue();

        assertThat(new HorizontalAutoscalingProperties(true, true).isExecutorPresent())
                .as("the two-argument shape means 'no executor'").isFalse();
    }

    @Test
    @DisplayName("the D3b retune sub-flag is independent and defaults off")
    void theRetuneSubFlagDefaultsOff() {
        assertThat(new HorizontalAutoscalingProperties(true, true, true).isTargetRetuneEnabled())
                .as("off even when the apply path is fully permitted").isFalse();
        assertThat(new HorizontalAutoscalingProperties(false, false, false, true).isTargetRetuneEnabled())
                .as("can be on while the master/readiness flags are off").isTrue();
    }

    @Test
    @DisplayName("uat is the ONLY profile where the horizontal switch and sub-flag are true")
    void onlyUatEnablesTheHpaPath() {
        for (String profile : PROFILES) {
            String contents = read(profile);
            String expected = ENABLED_PROFILE.equals(profile) ? "true" : "false";

            assertThat(activeValues(contents, HorizontalAutoscalingProperties.ENABLED_KEY))
                    .as("%s must declare the horizontal switch exactly once, as %s", profile, expected)
                    .containsExactly(expected);
            assertThat(activeValues(contents, HorizontalAutoscalingProperties.TARGET_RETUNE_KEY))
                    .as("%s must declare the D3b sub-flag exactly once, as %s", profile, expected)
                    .containsExactly(expected);
        }
    }

    @Test
    @DisplayName("no profile other than uat may ever enable the horizontal path")
    void noOtherProfileMayBeTurnedOn() {
        List<String> enabled = PROFILES.stream()
                .filter(profile -> {
                    String contents = read(profile);
                    return activeValues(contents, HorizontalAutoscalingProperties.ENABLED_KEY)
                            .contains("true")
                            || activeValues(contents, HorizontalAutoscalingProperties.TARGET_RETUNE_KEY)
                            .contains("true");
                })
                .toList();

        assertThat(enabled)
                .as("HPA automation may only be on in uat")
                .containsExactly(ENABLED_PROFILE);
    }

    @Test
    @DisplayName("the key names are the shared ones, not parallel inventions")
    void theKeysAreTheSharedOnes() {
        assertThat(HorizontalAutoscalingProperties.ENABLED_KEY)
                .as("the horizontal master switch is shared verbatim with the engine read path")
                .isEqualTo("xamops.automation.horizontal.enabled")
                .isEqualTo(WorkloadAutoscalingProperties.HORIZONTAL_ENABLED_KEY);
        assertThat(HorizontalAutoscalingProperties.READINESS_KEY)
                .as("the platform-wide HPA-apply readiness key the UI also reads")
                .isEqualTo("xamops.k8s.automation.readiness.hpa-apply");
        assertThat(HorizontalAutoscalingProperties.TARGET_RETUNE_KEY)
                .isEqualTo("xamops.automation.horizontal.target-retune.enabled");
    }

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
