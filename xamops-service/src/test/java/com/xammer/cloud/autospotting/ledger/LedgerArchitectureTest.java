package com.xammer.cloud.autospotting.ledger;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ledger code rules (plan-phase-15.md task 15.2; design-ledger.md §2.4, §9.1; FR-LED-13, FR-LED-17):
 * no class in {@code ..ledger..} calls {@code EntityManager.merge} or {@code remove} or declares
 * {@code @Modifying}; append-only repositories expose no delete or {@code saveAll}; every append-only
 * entity extends the insert-only base; and a source scan fails on {@code double}, {@code float},
 * their wrappers, {@code Math.round} and {@code DecimalFormat} in ledger source (comments excluded).
 */
class LedgerArchitectureTest {

    private static final Path MAIN = Path.of("src/main/java");
    /** Directories whose code is ledger code: a path segment {@code autospotting/ledger}. */
    private static final Pattern LEDGER_PATH = Pattern.compile("/autospotting/ledger/");
    private static final Pattern FORBIDDEN_TYPES = Pattern.compile("\\b(double|float|Double|Float)\\b");
    private static final Pattern FORBIDDEN_CALLS = Pattern.compile("\\bMath\\s*\\.\\s*round\\b|\\bDecimalFormat\\b"
            + "|\\bBigDecimal\\s*\\.\\s*valueOf\\s*\\(\\s*[^)]*\\.\\d|\\bnew\\s+BigDecimal\\s*\\(\\s*\\d+\\.\\d");

    private static JavaClasses ledgerClasses;

    @BeforeAll
    static void importClasses() {
        ledgerClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.xammer.cloud.domain.autospotting.ledger",
                        "com.xammer.cloud.repository.autospotting.ledger",
                        "com.xammer.cloud.service.autospotting.ledger");
    }

    @Test
    void ledgerPackagesAreNotEmpty() {
        assertThat(ledgerClasses.size()).isGreaterThan(20);
    }

    @Test
    void noLedgerClassCallsEntityManagerMergeOrRemove_orDeclaresModifying() {
        List<String> violations = new ArrayList<>();
        for (JavaClass c : ledgerClasses) {
            c.getMethodCallsFromSelf().stream()
                    .filter(call -> call.getTargetOwner().isAssignableTo(EntityManager.class))
                    .filter(call -> call.getName().equals("merge") || call.getName().equals("remove"))
                    .forEach(call -> violations.add(c.getName() + " calls EntityManager." + call.getName()));
            for (JavaMethod m : c.getMethods()) {
                if (m.isAnnotatedWith(Modifying.class)) {
                    violations.add(c.getName() + "." + m.getName() + " is @Modifying");
                }
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    void appendOnlyRepositoriesExposeNoDeleteOrSaveAll_andNoneIsAJpaRepository() {
        List<String> violations = new ArrayList<>();
        for (JavaClass c : ledgerClasses) {
            if (!c.isInterface() || !c.getPackageName().endsWith("repository.autospotting.ledger")) {
                continue;
            }
            if (c.isAssignableTo(JpaRepository.class)) {
                violations.add(c.getName() + " extends JpaRepository");
            }
            for (JavaMethod m : c.getAllMethods()) {
                String name = m.getName();
                if (name.startsWith("delete") || name.equals("saveAll") || name.startsWith("remove")
                        || name.equals("flush") && c.getSimpleName().equals("AppendOnlyRepository")) {
                    violations.add(c.getName() + " exposes " + name);
                }
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    void everyLedgerEntityExceptTheContractIsInsertOnly() {
        List<String> violations = new ArrayList<>();
        for (JavaClass c : ledgerClasses) {
            if (c.isAnnotatedWith(jakarta.persistence.Entity.class)
                    && !c.isAssignableTo(com.xammer.cloud.domain.autospotting.ledger.AppendOnlyEntity.class)
                    && !c.getSimpleName().equals("AutoSpottingContract")) {
                violations.add(c.getName());
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    void noLedgerSourceUsesFloatingPointOrBannedRounding() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> LEDGER_PATH.matcher(p.toString().replace('\\', '/')).find()).toList()) {
                violations.addAll(scan(file.toString(), Files.readString(file)));
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    void theScanCatchesWhatItShould() {
        assertThat(scan("x", "class A { double d = 1; }")).hasSize(1);
        assertThat(scan("x", "class A { Float f; float[] g; }")).hasSize(2);
        assertThat(scan("x", "class A { long r = Math.round(x); }")).hasSize(1);
        assertThat(scan("x", "class A { BigDecimal b = new BigDecimal(0.1); }")).hasSize(1);
        assertThat(scan("x", "class A { BigDecimal b = BigDecimal.valueOf(0.1); }")).hasSize(1);
        assertThat(scan("x", "class A { var f = new DecimalFormat(\"0.00\"); }")).hasSize(1);
        // Comments and string literals do not count; BigDecimal from a string or a long is allowed.
        assertThat(scan("x", "/** never a double */ class A { // float\n String s = \"double\"; "
                + "BigDecimal a = new BigDecimal(\"0.1\"); BigDecimal b = BigDecimal.valueOf(3600L); }")).isEmpty();
    }

    private static List<String> scan(String name, String source) {
        String code = stripCommentsAndStrings(source);
        List<String> found = new ArrayList<>();
        var types = FORBIDDEN_TYPES.matcher(code);
        while (types.find()) {
            found.add(name + ": " + types.group());
        }
        var calls = FORBIDDEN_CALLS.matcher(code);
        while (calls.find()) {
            found.add(name + ": " + calls.group().trim());
        }
        return found;
    }

    /** Removes block and line comments, and string and char literal contents (a minimal Java lexer). */
    static String stripCommentsAndStrings(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                int end = src.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
                out.append(' ');
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                int end = src.indexOf('\n', i);
                i = end < 0 ? n : end;
            } else if (c == '"' || c == '\'') {
                char quote = c;
                out.append(quote);
                i++;
                while (i < n && src.charAt(i) != quote) {
                    i += src.charAt(i) == '\\' ? 2 : 1;
                }
                out.append(quote);
                i++;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }
}
