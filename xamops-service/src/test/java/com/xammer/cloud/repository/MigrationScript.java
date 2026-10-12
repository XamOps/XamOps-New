package com.xammer.cloud.repository;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import com.xammer.cloud.domain.type.PgTestSupport;

/**
 * Reads a Flyway migration off the test classpath and applies it, for the tests that need the real
 * DDL rather than a hand-copied approximation of it.
 *
 * <p><strong>Both the comment stripping and the statement split have to be quote-aware</strong>,
 * which is why this is a scanner rather than a {@code split(";")}. The migrations here document
 * their own reversal with a {@code DROP TABLE … ;} line inside a comment, carry trailing {@code --}
 * notes on individual columns, and contain {@code COMMENT ON} strings with semicolons in them — so a
 * naive splitter both breaks statements apart and executes comments. Flyway's parser handles all
 * three; this one has to as well, or a test would report a problem the production path does not
 * have.
 *
 * <p>Extracted from {@code VerticalPolicyMigrationPgTest} so {@code SchemaUpdateNoAlterPgTest} can
 * build the same table from the same source of truth.
 */
final class MigrationScript {

    private final String resource;
    private final List<String> statements;

    private MigrationScript(String resource, List<String> statements) {
        this.resource = resource;
        this.statements = List.copyOf(statements);
    }

    /** @param resource classpath path, e.g. {@code /db/migration/V20261010_1__…sql} */
    static MigrationScript of(String resource) {
        return new MigrationScript(resource, parse(read(resource)));
    }

    List<String> statements() {
        return statements;
    }

    /** Applies every statement in order, in auto-commit, against a local PostgreSQL URL. */
    void applyTo(String url) {
        if (statements.isEmpty()) {
            throw new IllegalStateException(resource + " produced no statements");
        }
        try (Connection connection = PgTestSupport.connect(url); Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("migration " + resource + " failed to apply", e);
        }
    }

    private static List<String> parse(String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inString = false;

        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';

            if (inString) {
                current.append(c);
                if (c == '\'') {
                    if (next == '\'') {        // '' is an escaped quote, not the end of the literal
                        current.append(next);
                        i++;
                    } else {
                        inString = false;
                    }
                }
                continue;
            }
            if (c == '\'') {
                inString = true;
                current.append(c);
                continue;
            }
            if (c == '-' && next == '-') {     // line comment: skip to end of line
                while (i < sql.length() && sql.charAt(i) != '\n') {
                    i++;
                }
                current.append('\n');
                continue;
            }
            if (c == ';') {
                addIfPresent(statements, current);
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        addIfPresent(statements, current);
        return statements;
    }

    private static void addIfPresent(List<String> statements, StringBuilder candidate) {
        String trimmed = candidate.toString().strip();
        if (!trimmed.isEmpty()) {
            statements.add(trimmed);
        }
    }

    private static String read(String resource) {
        try (InputStream in = MigrationScript.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException(resource + " is not on the test classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
