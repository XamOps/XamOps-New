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
 * Both flags default to off, and no profile may quietly default them on.
 *
 * <p>The parity half reads the five {@code application*.properties} files from disk rather than
 * trusting the binding, because the failure mode being guarded against is somebody adding a profile
 * — or editing one — and leaving the engine enabled in it. A binding test cannot see that; reading
 * the files can.
 */
class WorkloadAutoscalingPropertiesTest {

    private static final List<String> PROFILES = List.of(
            "application.properties",
            "application-local.properties",
            "application-uat.properties",
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
    @DisplayName("auto-apply is false even with both flags on: there is no executor to apply with")
    void autoApplyIsFalseByConstruction() {
        WorkloadAutoscalingProperties bothOn = new WorkloadAutoscalingProperties(true, true);

        assertThat(bothOn.isEnabled()).isTrue();
        assertThat(bothOn.isReady()).isTrue();
        assertThat(bothOn.isAutoApplyPermitted())
                .as("the absence of a WorkloadActionExecutor, not a flag, is what prevents an apply")
                .isFalse();
    }

    @Test
    @DisplayName("both keys are present and literally false in every application*.properties")
    void everyProfileDeclaresBothFlagsAsFalse() {
        for (String profile : PROFILES) {
            String contents = read(profile);

            assertThat(contents)
                    .as("%s must declare the master switch explicitly", profile)
                    .contains(WorkloadAutoscalingProperties.ENABLED_KEY + "=false");
            assertThat(contents)
                    .as("%s must declare the readiness key explicitly", profile)
                    .contains(WorkloadAutoscalingProperties.READINESS_KEY + "=false");

            // Guards against a profile declaring the key twice with the second one winning.
            assertThat(activeValues(contents, WorkloadAutoscalingProperties.ENABLED_KEY))
                    .as("%s declares the master switch more than once", profile)
                    .containsExactly("false");
            assertThat(activeValues(contents, WorkloadAutoscalingProperties.READINESS_KEY))
                    .as("%s declares the readiness key more than once", profile)
                    .containsExactly("false");
        }
    }

    @Test
    @DisplayName("the key names are the ones the UI branch also writes, not parallel inventions")
    void theKeysAreTheSharedOnes() {
        assertThat(WorkloadAutoscalingProperties.ENABLED_KEY)
                .isEqualTo("xamops.automation.vertical.enabled");
        assertThat(WorkloadAutoscalingProperties.READINESS_KEY)
                .as("the platform-wide readiness namespace, so one flag flips the UI and the engine")
                .isEqualTo("xamops.k8s.automation.readiness.vertical-rightsizing");
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
