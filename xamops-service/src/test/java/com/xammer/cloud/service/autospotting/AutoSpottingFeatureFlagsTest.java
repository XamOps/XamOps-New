package com.xammer.cloud.service.autospotting;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The AutoSpotting safety switches default to off in code, so a profile without the property line
 * (the test profile, or one added later) is off too (ledger §12.1, AC-LED-19).
 */
class AutoSpottingFeatureFlagsTest {

    private static final Path MAIN_RESOURCES = Path.of("src/main/resources");

    @Test
    void everyFlagIsOffWithoutAPropertyLine() {
        AutoSpottingFeatureFlags flags = DefaultAutoSpottingFlags.load();

        assertThat(flags.isAwsConversionsEnabled()).isFalse();
        assertThat(flags.isGcpConversionsEnabled()).isFalse();
        assertThat(flags.isAwsSavingsLedgerEnabled()).isFalse();
        assertThat(flags.isGcpSavingsLedgerEnabled()).isFalse();
    }

    @Test
    void gcpSavingsLedgerFlagReadsItsOwnProperty() {
        new ApplicationContextRunner()
                .withBean(AutoSpottingFeatureFlags.class)
                .withPropertyValues("autospotting.gcp.savings-ledger.enabled=true")
                .run(context -> {
                    AutoSpottingFeatureFlags flags = context.getBean(AutoSpottingFeatureFlags.class);
                    assertThat(flags.isGcpSavingsLedgerEnabled()).isTrue();
                    assertThat(flags.isAwsSavingsLedgerEnabled()).isFalse();
                    assertThat(flags.isGcpConversionsEnabled()).isFalse();
                });
    }

    /** Each deployable profile states both legacy ledger switches as false, next to Phase A's lines. */
    @ParameterizedTest
    @ValueSource(strings = {
            "application.properties",
            "application-local.properties",
            "application-uat.properties",
            "application-preprod.properties",
            "application-prod.properties"})
    void everyProfileKeepsBothLegacyLedgerWritersOff(String file) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(MAIN_RESOURCES.resolve(file), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }

        assertThat(properties.getProperty("autospotting.gcp.savings-ledger.enabled")).isEqualTo("false");
        assertThat(properties.getProperty("autospotting.aws.savings-ledger.enabled")).isEqualTo("false");
    }
}
