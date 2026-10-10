package com.xammer.cloud.autospotting;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Migration rules for the AutoSpotting program (FR-ROL-04, AC-ROL-04, design-core.md §10):
 * an AutoSpotting migration never drops a table or column, never truncates, and never deletes or
 * updates without a WHERE clause. Phase A's {@code V20261006_1__remove_azure_autospotting.sql}
 * (the user's "remove azure autospotting completely") is the one exemption. No two migrations may
 * share a Flyway version, which mirrors the duplicate-version step of the CI job.
 */
class MigrationRulesTest {

    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");
    private static final Pattern VERSIONED = Pattern.compile("^V([0-9]+(?:[._][0-9]+)*)__(.+)\\.sql$");
    private static final Set<String> EXEMPT = Set.of("V20261006_1__remove_azure_autospotting.sql");

    private static final Pattern DROP_TABLE = Pattern.compile("\\bDROP\\s+TABLE\\b");
    private static final Pattern DROP_COLUMN = Pattern.compile("\\bDROP\\s+COLUMN\\b");
    private static final Pattern ALTER_TABLE = Pattern.compile("\\bALTER\\s+TABLE\\b");
    /** {@code ALTER TABLE t DROP c} drops a column too: COLUMN is optional in PostgreSQL. */
    private static final Pattern DROP_WITHOUT_COLUMN_KEYWORD = Pattern.compile(
            "\\bDROP\\s+(?!CONSTRAINT\\b|DEFAULT\\b|NOT\\b|EXPRESSION\\b|IDENTITY\\b|COLUMN\\b)[A-Z_\"]");
    private static final Pattern TRUNCATE = Pattern.compile("\\bTRUNCATE\\b");
    private static final Pattern DELETE = Pattern.compile("\\bDELETE\\s+FROM\\b");
    private static final Pattern UPDATE = Pattern.compile(
            "\\bUPDATE\\s+(?:ONLY\\s+)?[A-Z_\"][A-Z0-9_.\"]*(?:\\s+(?:AS\\s+)?[A-Z_][A-Z0-9_]*)?\\s+SET\\b");
    private static final Pattern WHERE = Pattern.compile("\\bWHERE\\b");

    @Test
    void autoSpottingMigrationsDropNothingAndNeverDeleteOrUpdateWithoutWhere() {
        List<Path> checked = autoSpottingMigrations().stream()
                .filter(p -> !EXEMPT.contains(p.getFileName().toString()))
                .toList();
        assertThat(checked).extracting(p -> p.getFileName().toString())
                .contains("V20261009_1__autospotting_legacy_savings_flag.sql");

        Map<String, List<String>> violations = new TreeMap<>();
        for (Path file : checked) {
            List<String> found = violations(read(file));
            if (!found.isEmpty()) {
                violations.put(file.getFileName().toString(), found);
            }
        }
        assertThat(violations).as("AutoSpotting migrations must be additive (FR-ROL-04)").isEmpty();
    }

    @Test
    void theOnlyExemptionIsPhaseAsAzureRemoval_andTheRulesCatchIt() {
        assertThat(EXEMPT).hasSize(1);
        Path exempt = MIGRATIONS.resolve(EXEMPT.iterator().next());
        assertThat(exempt).exists();
        // Proves the scanner reads real migrations: the exempt file does drop and delete.
        assertThat(violations(read(exempt))).contains("DROP TABLE");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "DROP TABLE vmss_spot_config;",
            "drop table if exists x cascade;",
            "ALTER TABLE t DROP COLUMN c;",
            "ALTER TABLE t DROP IF EXISTS c;",
            "alter table t drop c;",
            "TRUNCATE daily_spot_savings;",
            "DELETE FROM daily_spot_savings;",
            "DO $$ BEGIN IF to_regclass('t') IS NOT NULL THEN DELETE FROM t; END IF; END $$;",
            "UPDATE daily_spot_savings SET billable = true;",
            "UPDATE ONLY t AS x SET a = 1;",
            "IF EXISTS (SELECT 1 FROM t WHERE a) THEN UPDATE t SET a = 2; END IF;"})
    void scannerFlagsEachForbiddenForm(String sql) {
        assertThat(violations(sql)).isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ALTER TABLE t ADD COLUMN IF NOT EXISTS c BOOLEAN NOT NULL DEFAULT true;",
            "ALTER TABLE t DROP CONSTRAINT IF EXISTS ck;",
            "ALTER TABLE t ALTER COLUMN c DROP DEFAULT;",
            "ALTER TABLE t ALTER COLUMN c DROP NOT NULL;",
            "DROP TRIGGER IF EXISTS trg ON t; CREATE TRIGGER trg BEFORE UPDATE OR DELETE ON t "
                    + "FOR EACH ROW EXECUTE FUNCTION f();",
            "DELETE FROM t x USING cloud_account ca WHERE x.account_id = ca.id;",
            "UPDATE t SET a = 1 WHERE b = 2;",
            "-- DROP TABLE x; DELETE FROM y;\nSELECT 1;",
            "/* TRUNCATE t; */ SELECT 1;",
            "COMMENT ON COLUMN t.c IS 'Never DROP TABLE or UPDATE t SET anything';",
            "ALTER TABLE t ADD CONSTRAINT fk FOREIGN KEY (a) REFERENCES u(id) ON UPDATE CASCADE ON DELETE SET NULL;",
            "SELECT * FROM t FOR UPDATE;"})
    void scannerAllowsAdditiveAndGuardedForms(String sql) {
        assertThat(violations(sql)).isEmpty();
    }

    @Test
    void noTwoMigrationsShareAVersion() {
        Map<String, List<String>> byVersion = new LinkedHashMap<>();
        for (Path file : allVersionedMigrations()) {
            Matcher m = VERSIONED.matcher(file.getFileName().toString());
            assertThat(m.matches()).isTrue();
            byVersion.computeIfAbsent(normalizedVersion(m.group(1)), v -> new ArrayList<>())
                    .add(file.getFileName().toString());
        }
        Map<String, List<String>> duplicates = byVersion.entrySet().stream()
                .filter(e -> e.getValue().size() > 1)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        assertThat(duplicates).as("Flyway refuses to start when two migrations share a version").isEmpty();
        assertThat(byVersion).containsKeys("20261006.1", "20261009.1");
    }

    @Test
    void versionNormalizationMatchesFlyway() {
        assertThat(normalizedVersion("20261006_1")).isEqualTo(normalizedVersion("20261006.1"));
        assertThat(normalizedVersion("2")).isEqualTo(normalizedVersion("2_0"));
        assertThat(normalizedVersion("20260223_1500")).isNotEqualTo(normalizedVersion("20260223_15"));
    }

    // ── scanner ──────────────────────────────────────────────────────────────

    /** The forbidden forms found in a migration's SQL, ignoring comments and quoted strings. */
    static List<String> violations(String sql) {
        List<String> found = new ArrayList<>();
        for (String statement : stripCommentsAndStrings(sql).toUpperCase(Locale.ROOT).split(";")) {
            if (DROP_TABLE.matcher(statement).find()) {
                found.add("DROP TABLE");
            }
            if (DROP_COLUMN.matcher(statement).find() || (ALTER_TABLE.matcher(statement).find()
                    && DROP_WITHOUT_COLUMN_KEYWORD.matcher(statement).find() && !DROP_TABLE.matcher(statement).find())) {
                found.add("DROP COLUMN");
            }
            if (TRUNCATE.matcher(statement).find()) {
                found.add("TRUNCATE");
            }
            if (lacksWhereAfter(DELETE, statement)) {
                found.add("DELETE without WHERE");
            }
            if (lacksWhereAfter(UPDATE, statement)) {
                found.add("UPDATE without WHERE");
            }
        }
        return found;
    }

    private static boolean lacksWhereAfter(Pattern dml, String statement) {
        Matcher m = dml.matcher(statement);
        while (m.find()) {
            if (!WHERE.matcher(statement.substring(m.end())).find()) {
                return true;
            }
        }
        return false;
    }

    /** Drops -- and /* *&#47; comments and '...' literals; dollar-quoted bodies stay, since they hold code. */
    static String stripCommentsAndStrings(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                int end = sql.indexOf('\n', i);
                i = end < 0 ? sql.length() : end;
            } else if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? sql.length() : end + 2;
                out.append(' ');
            } else if (c == '\'') {
                i++;
                while (i < sql.length()) {
                    if (sql.charAt(i) == '\'' && i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                        i += 2;
                    } else if (sql.charAt(i) == '\'') {
                        i++;
                        break;
                    } else {
                        i++;
                    }
                }
                out.append("''");
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** Flyway compares versions numerically per part and ignores trailing zero parts. */
    static String normalizedVersion(String raw) {
        List<BigInteger> parts = new ArrayList<>(Arrays.stream(raw.split("[._]")).map(BigInteger::new).toList());
        while (parts.size() > 1 && parts.get(parts.size() - 1).signum() == 0) {
            parts.remove(parts.size() - 1);
        }
        return parts.stream().map(BigInteger::toString).collect(Collectors.joining("."));
    }

    // ── files ────────────────────────────────────────────────────────────────

    private static List<Path> allVersionedMigrations() {
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            return files.filter(p -> VERSIONED.matcher(p.getFileName().toString()).matches()).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Every migration with "autospotting" in its name: Phase A's, Phase B's, and the 2026-02 RI/SP one. */
    private static List<Path> autoSpottingMigrations() {
        return allVersionedMigrations().stream()
                .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).contains("autospotting"))
                .toList();
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
