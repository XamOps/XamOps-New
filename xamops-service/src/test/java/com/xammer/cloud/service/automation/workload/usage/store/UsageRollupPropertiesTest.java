package com.xammer.cloud.service.automation.workload.usage.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Defaults, clamps, and <strong>uat is the only profile where the rollups are on</strong>. The
 * parity half reads the five {@code application*.properties} files off disk, like
 * {@code WorkloadAutoscalingPropertiesTest}, because a binding test cannot see a profile that
 * somebody switched on by editing it.
 */
class UsageRollupPropertiesTest {

    private static final String ENABLED_PROFILE = "application-uat.properties";

    private static final List<String> PROFILES = List.of(
            "application.properties",
            "application-local.properties",
            ENABLED_PROFILE,
            "application-prod.properties",
            "application-preprod.properties");

    @Test
    @DisplayName("the code defaults are the documented ones, and the master switch is off")
    void defaults() {
        UsageRollupProperties p = withDefaults(false, 90, 730, 48, 2, 3, 3, 50);

        assertThat(p.enabled()).isFalse();
        assertThat(p.hourlyRetentionDays()).isEqualTo(90);
        assertThat(p.dailyRetentionDays()).isEqualTo(730);
        assertThat(p.sampleRetentionHours()).isEqualTo(48);
        assertThat(p.premakeMonths()).isEqualTo(2);
        assertThat(p.rollupLookbackHours()).isEqualTo(3);
        assertThat(p.dailyLookbackDays()).isEqualTo(3);
        assertThat(p.queueCapacity()).isEqualTo(50);
        assertThat(p.hourlyCron()).isEqualTo("0 7 * * * *");
        assertThat(p.maintenanceCron()).isEqualTo("0 30 1 * * *");
    }

    @Test
    @DisplayName("the @Value placeholders carry exactly those defaults")
    void placeholderDefaults() {
        Constructor<?> constructor = UsageRollupProperties.class.getConstructors()[0];
        List<String> placeholders = Arrays.stream(constructor.getParameters())
                .map(parameter -> parameter.getAnnotation(Value.class).value())
                .toList();

        assertThat(placeholders).containsExactly(
                "${xamops.k8s.usage-rollup.enabled:false}",
                "${xamops.k8s.usage-rollup.hourly-retention-days:90}",
                "${xamops.k8s.usage-rollup.daily-retention-days:730}",
                "${xamops.k8s.usage-rollup.sample-retention-hours:48}",
                "${xamops.k8s.usage-rollup.premake-months:2}",
                "${xamops.k8s.usage-rollup.rollup-lookback-hours:3}",
                "${xamops.k8s.usage-rollup.daily-lookback-days:3}",
                "${xamops.k8s.usage-rollup.queue-capacity:50}",
                "${xamops.k8s.usage-rollup.hourly-cron:0 7 * * * *}",
                "${xamops.k8s.usage-rollup.maintenance-cron:0 30 1 * * *}");
    }

    @Test
    @DisplayName("hourly retention is at least max(7, daily-lookback + 2) days")
    void hourlyRetentionClamp() {
        assertThat(withDefaults(true, 1, 730, 48, 2, 3, 3, 50).hourlyRetentionDays()).isEqualTo(7);
        assertThat(withDefaults(true, 7, 730, 48, 2, 3, 10, 50).hourlyRetentionDays())
                .as("a long daily lookback needs the hourly rows it re-reads")
                .isEqualTo(12);
        assertThat(withDefaults(true, 30, 730, 48, 2, 3, 10, 50).hourlyRetentionDays()).isEqualTo(30);
    }

    @Test
    @DisplayName("daily retention is at least the (clamped) hourly retention + 31 days")
    void dailyRetentionClamp() {
        assertThat(withDefaults(true, 90, 5, 48, 2, 3, 3, 50).dailyRetentionDays()).isEqualTo(121);
        assertThat(withDefaults(true, 120, 31, 48, 2, 3, 3, 50).dailyRetentionDays())
                .as("daily below hourly would re-create, then drop, daily partitions in one run")
                .isEqualTo(151);
        assertThat(withDefaults(true, 1, 5, 48, 2, 3, 3, 50).dailyRetentionDays())
                .as("measured from the clamped hourly retention (7)").isEqualTo(38);
        assertThat(withDefaults(true, 90, 121, 48, 2, 3, 3, 50).dailyRetentionDays()).isEqualTo(121);
        assertThat(withDefaults(true, 90, 730, 48, 2, 3, 3, 50).dailyRetentionDays()).isEqualTo(730);
    }

    @Test
    @DisplayName("sample retention is at least rollup-lookback + 24 hours")
    void sampleRetentionClamp() {
        assertThat(withDefaults(true, 90, 730, 1, 2, 3, 3, 50).sampleRetentionHours()).isEqualTo(27);
        assertThat(withDefaults(true, 90, 730, 30, 2, 12, 3, 50).sampleRetentionHours()).isEqualTo(36);
        assertThat(withDefaults(true, 90, 730, 72, 2, 3, 3, 50).sampleRetentionHours()).isEqualTo(72);
    }

    @Test
    @DisplayName("premake-months is clamped to 1..12")
    void premakeClamp() {
        assertThat(withDefaults(true, 90, 730, 48, 0, 3, 3, 50).premakeMonths()).isEqualTo(1);
        assertThat(withDefaults(true, 90, 730, 48, -4, 3, 3, 50).premakeMonths()).isEqualTo(1);
        assertThat(withDefaults(true, 90, 730, 48, 13, 3, 3, 50).premakeMonths()).isEqualTo(12);
        assertThat(withDefaults(true, 90, 730, 48, 12, 3, 3, 50).premakeMonths()).isEqualTo(12);
    }

    @Test
    @DisplayName("queue-capacity is clamped to 1..1000")
    void queueClamp() {
        assertThat(withDefaults(true, 90, 730, 48, 2, 3, 3, 0).queueCapacity()).isEqualTo(1);
        assertThat(withDefaults(true, 90, 730, 48, 2, 3, 3, 5000).queueCapacity()).isEqualTo(1000);
        assertThat(withDefaults(true, 90, 730, 48, 2, 3, 3, 1000).queueCapacity()).isEqualTo(1000);
    }

    @Test
    @DisplayName("uat is the ONLY profile where the rollups are enabled; the others omit the key or say false")
    void onlyUatEnablesTheRollups() {
        for (String profile : PROFILES) {
            List<String> values = activeValues(read(profile), UsageRollupProperties.ENABLED_KEY);
            if (ENABLED_PROFILE.equals(profile)) {
                assertThat(values).as("%s declares the switch exactly once, as true", profile)
                        .containsExactly("true");
            } else {
                assertThat(values).as("%s must not switch the rollups on", profile)
                        .allMatch("false"::equals);
            }
        }
    }

    @Test
    @DisplayName("the key name is the one the plan fixed, distinct from the parallel usage-store branch")
    void keyName() {
        assertThat(UsageRollupProperties.ENABLED_KEY).isEqualTo("xamops.k8s.usage-rollup.enabled");
    }

    private static UsageRollupProperties withDefaults(boolean enabled, int hourly, int daily, int sampleHours,
                                                      int premake, int lookbackHours, int lookbackDays,
                                                      int queue) {
        return new UsageRollupProperties(enabled, hourly, daily, sampleHours, premake, lookbackHours,
                lookbackDays, queue, UsageRollupProperties.DEFAULT_HOURLY_CRON,
                UsageRollupProperties.DEFAULT_MAINTENANCE_CRON);
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
