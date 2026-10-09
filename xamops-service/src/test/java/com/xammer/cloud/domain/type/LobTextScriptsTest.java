package com.xammer.cloud.domain.type;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Static guards on the manual cutover SQL (design §3.7, D14, D15; AC-25, AC-30). No database and no
 * psql is involved, so this test runs in CI.
 *
 * <p>The scripts are operator tools: they live only under {@code scripts/db/lob-text/}, they are never
 * referenced from application code, the image build or the pipeline, and nothing in them can delete a
 * large object.
 */
class LobTextScriptsTest {

    /** The module directory is the working directory of a surefire run. */
    private static final Path REPO = Path.of("..");
    private static final Path SCRIPTS = REPO.resolve("scripts/db/lob-text");

    private static final String CENSUS = "census-lob-text.sql";
    private static final String NORMALIZE = "normalize-lob-text-columns.sql";
    private static final String ROLLBACK = "rollback-lob-text.sql";
    private static final String DEV_OPS = "dev-ops-script-lob-to-inline.sql";
    private static final List<String> SCRIPT_NAMES = List.of(CENSUS, NORMALIZE, ROLLBACK, DEV_OPS);

    /** Comment markers that the guards below treat as "this line is documentation". */
    private static final List<String> HEADINGS =
            List.of("Purpose", "Preconditions", "Window / lock impact", "What changes", "Rollback", "Post-steps");

    // ------------------------------------------------------------------ AC-25: location

    @ParameterizedTest
    @ValueSource(strings = {CENSUS, NORMALIZE, ROLLBACK, DEV_OPS})
    void theScriptIsUnderScriptsDbLobText(String name) {
        assertThat(SCRIPTS.resolve(name)).as("run from the xamops-service module directory").isRegularFile();
    }

    @Test
    void scriptsDbLobTextHoldsNothingElse() throws IOException {
        try (Stream<Path> files = Files.list(SCRIPTS)) {
            assertThat(files.map(p -> p.getFileName().toString()).sorted())
                    .containsExactlyInAnyOrderElementsOf(SCRIPT_NAMES);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {CENSUS, NORMALIZE, ROLLBACK, DEV_OPS})
    void theScriptIsNotAtTheRepositoryRootOrUnderDbMigration(String name) {
        assertThat(REPO.resolve(name)).as("repository root").doesNotExist();
        for (String module : List.of("xamops-service", "billops")) {
            assertThat(REPO.resolve(module + "/src/main/resources/db/migration/" + name))
                    .as("%s Flyway directory", module).doesNotExist();
        }
    }

    @Test
    void noFlywayMigrationMentionsTheCutoverScripts() throws IOException {
        for (String module : List.of("xamops-service", "billops")) {
            Path migrations = REPO.resolve(module + "/src/main/resources/db/migration");
            if (!Files.isDirectory(migrations)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(migrations)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    assertThat(read(file)).as("%s", file).doesNotContain("lob-text");
                }
            }
        }
    }

    // ------------------------------------------------------------------ AC-30: no large object is destroyed

    @ParameterizedTest
    @ValueSource(strings = {CENSUS, NORMALIZE, ROLLBACK, DEV_OPS})
    void loUnlinkAndVacuumloAppearOnlyOnHeaderWarningLines(String name) {
        List<String> offenders = new ArrayList<>();
        for (String line : lines(name)) {
            String trimmed = line.stripLeading();
            if ((line.contains("lo_unlink") || line.contains("vacuumlo")) && !trimmed.startsWith("--")) {
                offenders.add(line);
            }
        }
        assertThat(offenders).as("%s may only name lo_unlink/vacuumlo in a '--' comment", name).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {CENSUS, NORMALIZE, ROLLBACK, DEV_OPS})
    void theHeaderWarnsAboutVacuumlo(String name) {
        String source = read(SCRIPTS.resolve(name));
        assertThat(source).as("%s header", name)
                .contains("lo_unlink and vacuumlo are never used")
                .contains("Do not");
    }

    // ------------------------------------------------------------------ §3.7: the common shape

    @ParameterizedTest
    @ValueSource(strings = {CENSUS, NORMALIZE, ROLLBACK, DEV_OPS})
    void theHeaderCoversEverySection(String name) {
        List<String> comments = lines(name).stream().map(String::stripLeading)
                .filter(l -> l.startsWith("--")).map(l -> l.substring(2).trim()).toList();
        for (String heading : HEADINGS) {
            assertThat(comments).as("%s header section '%s'", name, heading).contains(heading);
        }
        assertThat(String.join("\n", comments)).as("%s psql requirement", name).contains("psql --version");
    }

    @ParameterizedTest
    @ValueSource(strings = {CENSUS, NORMALIZE, ROLLBACK, DEV_OPS})
    void theScriptStopsOnTheFirstErrorAndRunsInOneTransaction(String name) {
        List<String> statements = lines(name).stream().map(String::stripLeading)
                .filter(l -> !l.isEmpty() && !l.startsWith("--")).toList();
        assertThat(statements).as("%s", name).isNotEmpty();
        assertThat(statements.get(0)).isEqualTo("\\set ON_ERROR_STOP on");
        // Only the outer transaction control ends in a semicolon; a PL/pgSQL BEGIN never does.
        assertThat(lines(name).stream().filter(l -> l.equals("BEGIN;") || l.equals("BEGIN READ ONLY;")).count())
                .as("%s outer BEGIN", name).isEqualTo(1);
        assertThat(lines(name).stream().filter(l -> l.equals("COMMIT;")).count())
                .as("%s outer COMMIT", name).isEqualTo(1);
        assertThat(statements).as("%s must never ROLLBACK by hand", name)
                .noneMatch(l -> l.startsWith("ROLLBACK"));
    }

    @Test
    void onlyTheCensusIsReadOnlyAndOnlyItNeedsPsql10() {
        assertThat(read(SCRIPTS.resolve(CENSUS))).contains("BEGIN READ ONLY;").contains("\\if :{?since_oid}")
                .contains("psql 10 or newer");
        for (String name : List.of(NORMALIZE, ROLLBACK, DEV_OPS)) {
            assertThat(read(SCRIPTS.resolve(name))).as("%s", name)
                    .doesNotContain("READ ONLY").doesNotContain("\\if ").contains("Any psql");
        }
    }

    @Test
    void everyWritingScriptConfirmsThePhraseAndCapsTheLockWait() {
        assertThat(read(SCRIPTS.resolve(NORMALIZE))).contains("august-retired-and-inline-mode")
                .contains("SET LOCAL lock_timeout = '5s';").contains("lobtext.fail_after_convert");
        assertThat(read(SCRIPTS.resolve(DEV_OPS))).contains("convert-dev-ops-script")
                .contains("SET LOCAL lock_timeout = '5s';").contains("lobtext.fail_after_convert");
        assertThat(read(SCRIPTS.resolve(ROLLBACK))).contains("rollback-lob-text")
                .contains("SET LOCAL lock_timeout = '5s';");
        assertThat(read(SCRIPTS.resolve(CENSUS))).as("the census needs no confirmation")
                .doesNotContain("lobtext.confirm");
    }

    @Test
    void theWritingScriptsShareOneBackupTable() {
        for (String name : List.of(NORMALIZE, DEV_OPS, ROLLBACK)) {
            assertThat(read(SCRIPTS.resolve(name))).as("%s", name).contains("lob_text_backup_20261005");
        }
    }

    // ------------------------------------------------------------------ AC-25: nothing automated runs them

    @Test
    void noApplicationCodePipelineOrImageReferencesTheScripts() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : automatedFiles()) {
            if (read(file).contains("scripts/db/lob-text")) {
                offenders.add(file.toString());
            }
        }
        assertThat(offenders).as("only docs, scripts, tests and the superseded root script may name the scripts")
                .isEmpty();
    }

    /** Everything that runs without an operator: application code, the pipeline and the image builds. */
    private static List<Path> automatedFiles() throws IOException {
        List<Path> files = new ArrayList<>();
        addTree(files, REPO.resolve(".github"));
        addFile(files, REPO.resolve("Jenkinsfile"));
        try (Stream<Path> top = Files.list(REPO)) {
            for (Path entry : top.toList()) {
                String name = entry.getFileName().toString();
                if (Files.isRegularFile(entry) && name.startsWith("Dockerfile")) {
                    files.add(entry);
                } else if (Files.isDirectory(entry) && !name.startsWith(".")) {
                    addTree(files, entry.resolve("src/main"));
                    try (Stream<Path> inModule = Files.list(entry)) {
                        inModule.filter(Files::isRegularFile)
                                .filter(p -> p.getFileName().toString().startsWith("Dockerfile"))
                                .forEach(files::add);
                    }
                }
            }
        }
        return files;
    }

    private static void addTree(List<Path> files, Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> tree = Files.walk(root, FileVisitOption.FOLLOW_LINKS)) {
            tree.filter(Files::isRegularFile).forEach(files::add);
        }
    }

    private static void addFile(List<Path> files, Path file) {
        if (Files.isRegularFile(file)) {
            files.add(file);
        }
    }

    private static List<String> lines(String name) {
        return read(SCRIPTS.resolve(name)).lines().toList();
    }

    /** ISO-8859-1 never fails to decode, which matters when the sweep above meets a binary file. */
    private static String read(Path file) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            return new String(bytes, file.toString().endsWith(".sql")
                    ? StandardCharsets.UTF_8 : StandardCharsets.ISO_8859_1);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + file, e);
        }
    }
}
