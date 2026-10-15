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
 * Both flags default to off, auto-apply needs three things rather than two, and
 * <strong>uat is the only profile where either flag is true</strong>.
 *
 * <p>The parity half reads the five {@code application*.properties} files from disk rather than
 * trusting the binding, because the failure mode being guarded against is somebody adding a profile
 * — or editing one — and leaving the engine enabled in it. A binding test cannot see that; reading
 * the files can.
 */
class WorkloadAutoscalingPropertiesTest {

    /** The one profile where this feature is switched on. */
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
        WorkloadAutoscalingProperties properties = new WorkloadAutoscalingProperties(false, false);

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.isReady()).isFalse();
    }

    @Test
    @DisplayName("auto-apply needs both flags AND an executor: any one missing makes it false")
    void autoApplyNeedsBothFlagsAndAnExecutor() {
        // This case used to assert auto-apply was false even with both flags on, because no
        // WorkloadActionExecutor shipped and the guard was the constant false. One ships now, so the
        // third condition is a real question -- and it is still a real guard, asserted rather than
        // described: the truth table below is the whole claim.
        assertThat(new WorkloadAutoscalingProperties(false, false, true).isAutoApplyPermitted())
                .as("master switch off")
                .isFalse();
        assertThat(new WorkloadAutoscalingProperties(true, false, true).isAutoApplyPermitted())
                .as("readiness off: recommendations are allowed long before an apply is")
                .isFalse();
        assertThat(new WorkloadAutoscalingProperties(false, true, true).isAutoApplyPermitted())
                .as("ready but not enabled")
                .isFalse();
        assertThat(new WorkloadAutoscalingProperties(true, true, false).isAutoApplyPermitted())
                .as("both flags on but no executor: there is nothing that could carry the apply")
                .isFalse();

        WorkloadAutoscalingProperties permitted = new WorkloadAutoscalingProperties(true, true, true);
        assertThat(permitted.isEnabled()).isTrue();
        assertThat(permitted.isReady()).isTrue();
        assertThat(permitted.isExecutorPresent()).isTrue();
        assertThat(permitted.isAutoApplyPermitted()).isTrue();

        assertThat(new WorkloadAutoscalingProperties(true, true).isExecutorPresent())
                .as("the two-argument shape means 'no executor', for every test that is not about one")
                .isFalse();
    }

    @Test
    @DisplayName("uat is the ONLY profile where either flag is true; the other four are literally false")
    void onlyUatEnablesTheEngine() {
        for (String profile : PROFILES) {
            String contents = read(profile);
            String expected = ENABLED_PROFILE.equals(profile) ? "true" : "false";

            // containsExactly, not contains: a key declared twice would let the second one win
            // silently, which is exactly how a profile acquires a value nobody chose.
            assertThat(activeValues(contents, WorkloadAutoscalingProperties.ENABLED_KEY))
                    .as("%s must declare the master switch exactly once, as %s", profile, expected)
                    .containsExactly(expected);
            assertThat(activeValues(contents, WorkloadAutoscalingProperties.READINESS_KEY))
                    .as("%s must declare the readiness key exactly once, as %s", profile, expected)
                    .containsExactly(expected);
            assertThat(activeValues(contents, WorkloadAutoscalingProperties.HORIZONTAL_ENABLED_KEY))
                    .as("%s must declare the horizontal switch exactly once, as %s", profile, expected)
                    .containsExactly(expected);
        }
    }

    @Test
    @DisplayName("no profile other than uat may ever be switched on")
    void noOtherProfileMayBeTurnedOn() {
        // The invariant in one assertion, so the failure message names the offending profile rather
        // than a line number: prod, preprod, local and the default profile stay off until somebody
        // changes this test on purpose.
        List<String> enabled = PROFILES.stream()
                .filter(profile -> {
                    String contents = read(profile);
                    return activeValues(contents, WorkloadAutoscalingProperties.ENABLED_KEY)
                            .contains("true")
                            || activeValues(contents, WorkloadAutoscalingProperties.READINESS_KEY)
                            .contains("true")
                            || activeValues(contents, WorkloadAutoscalingProperties.HORIZONTAL_ENABLED_KEY)
                            .contains("true");
                })
                .toList();

        assertThat(enabled)
                .as("workload vertical rightsizing may only be on in uat")
                .containsExactly(ENABLED_PROFILE);
    }

    @Test
    @DisplayName("the key names are the ones the UI branch also writes, not parallel inventions")
    void theKeysAreTheSharedOnes() {
        assertThat(WorkloadAutoscalingProperties.ENABLED_KEY)
                .isEqualTo("xamops.automation.vertical.enabled");
        assertThat(WorkloadAutoscalingProperties.READINESS_KEY)
                .as("the platform-wide readiness namespace, so one flag flips the UI and the engine")
                .isEqualTo("xamops.k8s.automation.readiness.vertical-rightsizing");
        assertThat(WorkloadAutoscalingProperties.HORIZONTAL_ENABLED_KEY)
                .as("the HPA master switch, parallel to the vertical one")
                .isEqualTo("xamops.automation.horizontal.enabled");
    }

    @Test
    @DisplayName("the horizontal switch defaults off and is independent of the vertical flags")
    void theHorizontalSwitchDefaultsOff() {
        assertThat(new WorkloadAutoscalingProperties(true, true).isHorizontalEnabled())
                .as("the two- and three-argument shapes mean 'horizontal off'")
                .isFalse();
        assertThat(new WorkloadAutoscalingProperties(true, true, true).isHorizontalEnabled()).isFalse();
        assertThat(new WorkloadAutoscalingProperties(false, false, false, true).isHorizontalEnabled())
                .as("horizontal can be on while the vertical flags are off")
                .isTrue();
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
