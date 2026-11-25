package com.xammer.cloud.xampulse.service.platform;

import com.xammer.cloud.xampulse.service.platform.ClickHouseMigrationRunner.Script;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ClickHouseMigrationRunnerTest {

    static final class FakeDdl implements ClickHouseDdl {
        final List<String> executed = new ArrayList<>();
        final Map<String, String> applied = new HashMap<>();

        @Override public void execute(String sql) { executed.add(sql); }

        @Override public Map<String, String> applied() { return new HashMap<>(applied); }

        @Override public void markApplied(String version, String checksum) { applied.put(version, checksum); }
    }

    private final List<Script> scripts = List.of(
            new Script("V002", "metrics", "CREATE TABLE b (x Int8) ENGINE = {{REPLICATED}}MergeTree ORDER BY x;"),
            new Script("V001", "logs", "-- a comment; with a semicolon\nCREATE TABLE a (s String DEFAULT 'a;b') ENGINE = {{REPLICATED}}MergeTree ORDER BY s;\n"
                    + "CREATE VIEW v AS SELECT 1;"),
            new Script("V010", "late", "CREATE TABLE c (x Int8) ENGINE = MergeTree ORDER BY x"));

    @Test
    void appliesScriptsInNumericOrderSplittingStatementsAndSubstitutingTheEngine() throws SQLException {
        FakeDdl ddl = new FakeDdl();
        var res = new ClickHouseMigrationRunner(ddl, null, false).run(scripts);
        assertEquals(List.of("V001", "V002", "V010"), res.applied());
        // bootstrap table first, then V001's two statements, V002, V010
        assertEquals(1 + 2 + 1 + 1, ddl.executed.size());
        assertTrue(ddl.executed.get(0).contains("xp_schema_version"));
        assertTrue(ddl.executed.get(1).contains("DEFAULT 'a;b'"), "semicolon inside a string literal must not split");
        assertTrue(ddl.executed.get(1).contains("ENGINE = MergeTree"));
        assertFalse(ddl.executed.stream().anyMatch(s -> s.contains("{{REPLICATED}}")));
        assertFalse(ddl.executed.stream().anyMatch(s -> s.contains("a comment")));
        assertEquals(3, ddl.applied.size());
    }

    @Test
    void replicatedModeUsesReplicatedEngines() throws SQLException {
        FakeDdl ddl = new FakeDdl();
        new ClickHouseMigrationRunner(ddl, null, true).run(scripts);
        assertTrue(ddl.executed.stream().anyMatch(s -> s.contains("ENGINE = ReplicatedMergeTree ORDER BY x")));
        assertTrue(ddl.executed.get(0).contains("ReplicatedMergeTree"));
    }

    @Test
    void secondRunIsIdempotentAndReportsEditedScripts() throws SQLException {
        FakeDdl ddl = new FakeDdl();
        ClickHouseMigrationRunner runner = new ClickHouseMigrationRunner(ddl, null, false);
        runner.run(scripts);
        int afterFirst = ddl.executed.size();
        var second = runner.run(scripts);
        assertTrue(second.applied().isEmpty());
        assertEquals(3, second.alreadyApplied().size());
        assertTrue(second.checksumChanged().isEmpty());
        assertEquals(afterFirst + 1, ddl.executed.size(), "only the idempotent bootstrap statement runs again");

        List<Script> edited = new ArrayList<>(scripts);
        edited.set(0, new Script("V002", "metrics", "CREATE TABLE b (x Int16) ENGINE = MergeTree ORDER BY x;"));
        var third = runner.run(edited);
        assertEquals(List.of("V002"), third.checksumChanged());
        assertTrue(third.applied().isEmpty(), "applied scripts are immutable: never re-run");
    }

    @Test
    void shippedScriptsLoadFromTheClasspathInOrder() throws Exception {
        List<Script> shipped = ClickHouseMigrationRunner.loadClasspathScripts();
        List<String> versions = shipped.stream().map(Script::version).sorted().toList();
        assertEquals(List.of("V001", "V002", "V004", "V005", "V006", "V007", "V008", "V009", "V010", "V011"), versions);
    }
}
