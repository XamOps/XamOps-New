package com.xammer.billops.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC 15 and design D10 for billops: the LOB-text write mode (and the FR-2 startup-job and
 * convert-on-read flags) are set by environment variables only, never by a committed profile file.
 * Keys are compared in relaxed form (case, dashes and underscores ignored), and failure messages name
 * the file and key only, never a value.
 */
class ProfileFlagsTest {

    private static final Path RESOURCES = Path.of("src/main/resources");

    /** Exact keys a profile must not set. */
    private static final List<String> FLAGS = List.of(
            "gcp.billing.net-cost-cache-migration.enabled",
            "gcp.billing.history-snapshot-on-startup.enabled",
            "aws.billing.history-snapshot-on-startup.enabled",
            "xammer.lob-text.convert-on-read.enabled");

    /** Key prefixes a profile must not set. */
    private static final List<String> PREFIXES = List.of(
            "xammer.lob-text.",
            "spring.jpa.properties.xammer.lob-text.");

    @Test
    void noProfileFileSetsALobTextSettingOrAStartupJobFlag() throws IOException {
        List<Path> profiles = profileFiles();
        assertThat(profiles).as("profile files under %s", RESOURCES)
                .extracting(p -> p.getFileName().toString())
                .contains("application-local.properties", "application-uat.properties",
                        "application-prod.properties", "application-preprod.properties", "application-sit.properties");

        List<String> violations = new ArrayList<>();
        for (Path profile : profiles) {
            for (String key : load(profile).stringPropertyNames()) {
                if (isGuarded(key)) {
                    violations.add(profile.getFileName() + " sets " + key);
                }
            }
        }
        assertThat(violations).as("the write mode is set by environment variables only (design D10)").isEmpty();
    }

    @Test
    void theRelaxedMatchCatchesEveryFormOfTheGuardedKeys() {
        assertThat(isGuarded("xammer.lob-text.write-mode")).isTrue();
        assertThat(isGuarded("xammer.lobText.writeMode")).isTrue();
        assertThat(isGuarded("XAMMER.LOB_TEXT.WRITE_MODE")).isTrue();
        assertThat(isGuarded("spring.jpa.properties.xammer.lob-text.write-mode")).isTrue();
        assertThat(isGuarded("aws.billing.historySnapshotOnStartup.enabled")).isTrue();
        assertThat(isGuarded("xamops.messaging.enabled")).isFalse();
        assertThat(isGuarded("spring.jpa.properties.hibernate.dialect")).isFalse();
    }

    // ------------------------------------------------------------------ helpers

    private static boolean isGuarded(String key) {
        String k = relaxed(key);
        return FLAGS.stream().anyMatch(f -> relaxed(f).equals(k))
                || PREFIXES.stream().anyMatch(p -> k.startsWith(relaxed(p)));
    }

    /** Lower case, without dashes or underscores: the forms Spring's relaxed binding treats alike. */
    private static String relaxed(String key) {
        return key.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
    }

    private static List<Path> profileFiles() throws IOException {
        assertThat(RESOURCES).as("run from the module directory").isDirectory();
        try (Stream<Path> files = Files.list(RESOURCES)) {
            return files.filter(p -> p.getFileName().toString().matches("application-.+\\.properties")).sorted().toList();
        }
    }

    private static Properties load(Path file) throws IOException {
        Properties properties = new Properties();
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(in);
        }
        return properties;
    }
}
