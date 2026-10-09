package com.xammer.cloud.config;

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
 * AC 15, design D10 and design §8.0, in two halves.
 *
 * <p><b>Profile half:</b> the startup-job flags, the convert-on-read flag and the LOB-text write mode
 * are set by environment variables only, never by a committed profile file. Keys are compared in
 * relaxed form (case, dashes and underscores ignored), and failure messages name the file and key
 * only, never a value.
 *
 * <p><b>Runbook half:</b> {@code ../docs/runbooks/lob-text-cutover.md} documents all six cutover
 * settings, each with its property name, its environment variable and its default, so an operator
 * never has to read the code to find out what a flag does or what it defaults to.
 */
class ProfileFlagsTest {

    private static final Path RESOURCES = Path.of("src/main/resources");

    /** The cutover runbook, relative to this module directory (design §8, plan step 5.1). */
    private static final Path RUNBOOK = Path.of("../docs/runbooks/lob-text-cutover.md");

    /**
     * The six settings of the design §8.0 flag table: property name, environment variable, default.
     * The default is matched as text, because the runbook states it in a table cell.
     */
    private static final List<String[]> RUNBOOK_FLAGS = List.of(
            new String[] {"xammer.lob-text.write-mode", "XAMMER_LOBTEXT_WRITEMODE", "legacy"},
            new String[] {"xammer.lob-text.convert-on-read.enabled",
                    "XAMMER_LOBTEXT_CONVERTONREAD_ENABLED", "false"},
            new String[] {"gcp.billing.net-cost-cache-migration.enabled",
                    "GCP_BILLING_NETCOSTCACHEMIGRATION_ENABLED", "false"},
            new String[] {"gcp.billing.history-snapshot-on-startup.enabled",
                    "GCP_BILLING_HISTORYSNAPSHOTONSTARTUP_ENABLED", "false"},
            new String[] {"aws.billing.history-snapshot-on-startup.enabled",
                    "AWS_BILLING_HISTORYSNAPSHOTONSTARTUP_ENABLED", "false"},
            new String[] {"spring.jpa.hibernate.ddl-auto", "SPRING_JPA_HIBERNATE_DDLAUTO", "update"});

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
    void noProfileFileSetsAStartupJobFlagOrALobTextSetting() throws IOException {
        List<Path> profiles = profileFiles();
        assertThat(profiles).as("profile files under %s", RESOURCES)
                .extracting(p -> p.getFileName().toString())
                .contains("application-local.properties", "application-uat.properties",
                        "application-prod.properties", "application-preprod.properties");

        List<String> violations = new ArrayList<>();
        for (Path profile : profiles) {
            for (String key : load(profile).stringPropertyNames()) {
                if (isGuarded(key)) {
                    violations.add(profile.getFileName() + " sets " + key);
                }
            }
        }
        assertThat(violations).as("flags are enabled by environment variables only (design D10)").isEmpty();
    }

    @Test
    void theRelaxedMatchCatchesEveryFormOfTheGuardedKeys() {
        assertThat(isGuarded("gcp.billing.net-cost-cache-migration.enabled")).isTrue();
        assertThat(isGuarded("gcp.billing.netCostCacheMigration.enabled")).isTrue();
        assertThat(isGuarded("GCP.BILLING.NET_COST_CACHE_MIGRATION.ENABLED")).isTrue();
        assertThat(isGuarded("xammer.lob-text.write-mode")).isTrue();
        assertThat(isGuarded("xammer.lobText.writeMode")).isTrue();
        assertThat(isGuarded("spring.jpa.properties.xammer.lob-text.write-mode")).isTrue();
        assertThat(isGuarded("gcp.billing.export-table-name")).isFalse();
        assertThat(isGuarded("spring.jpa.properties.hibernate.jdbc.batch_size")).isFalse();
    }

    // ------------------------------------------------------- AC 15, runbook half

    @Test
    void theRunbookDocumentsEveryFlagWithItsEnvVarAndDefault() throws IOException {
        assertThat(RUNBOOK).as("the cutover runbook (design §8, plan step 5.1)").isRegularFile();
        String runbook = Files.readString(RUNBOOK, StandardCharsets.UTF_8);

        List<String> missing = new ArrayList<>();
        for (String[] flag : RUNBOOK_FLAGS) {
            if (!runbook.contains(flag[0])) {
                missing.add("property " + flag[0]);
            }
            if (!runbook.contains(flag[1])) {
                missing.add("env var " + flag[1]);
            }
            // The default must appear on a line that also names the property, i.e. in its table row.
            boolean documented = runbook.lines()
                    .anyMatch(line -> line.contains(flag[0]) && line.contains(flag[2]));
            if (!documented) {
                missing.add("default of " + flag[0]);
            }
        }
        assertThat(missing).as("%s documents each flag's name, env var and default", RUNBOOK).isEmpty();
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
