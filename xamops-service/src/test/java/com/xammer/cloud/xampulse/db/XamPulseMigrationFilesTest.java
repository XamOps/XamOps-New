package com.xammer.cloud.xampulse.db;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Structural checks on the Postgres migrations (read straight from the source tree). */
class XamPulseMigrationFilesTest {

    private static final Path DIR = Path.of("src/main/resources/db/migration");
    private static final Pattern NAME = Pattern.compile("^V(\\d{8})_(\\d+)__(.+)\\.sql$");
    /** Tables that join to their tenant through a parent row instead of carrying cloud_account_id. */
    private static final Set<String> PARENT_JOINED = Set.of("xp_agent_credential", "xp_alert_state", "xp_incident_event", "xp_migration_log");

    private static long key(Path p) {
        Matcher m = NAME.matcher(p.getFileName().toString());
        assertTrue(m.matches(), p.getFileName().toString());
        return Long.parseLong(m.group(1)) * 1_000_000L + Long.parseLong(m.group(2));
    }

    private static List<Path> xamPulse() throws IOException {
        try (Stream<Path> s = Files.list(DIR)) {
            return s.filter(p -> p.getFileName().toString().contains("__xampulse_")).sorted(Comparator.comparingLong(XamPulseMigrationFilesTest::key)).toList();
        }
    }

    @Test
    void sevenXamPulseMigrationsExistAndSortAfterEveryOtherMigration() throws IOException {
        List<Path> ours = xamPulse();
        assertEquals(7, ours.size());
        long min = ours.stream().mapToLong(XamPulseMigrationFilesTest::key).min().orElseThrow();
        try (Stream<Path> all = Files.list(DIR)) {
            long highestOther = all.filter(p -> !p.getFileName().toString().contains("__xampulse_"))
                    .filter(p -> NAME.matcher(p.getFileName().toString()).matches())
                    .mapToLong(XamPulseMigrationFilesTest::key).max().orElseThrow();
            assertTrue(min > highestOther, "XamPulse migrations must come after the previous highest version");
        }
    }

    @Test
    void migrationsAreIdempotentJsonFreeAndTenantKeyed() throws IOException {
        Pattern create = Pattern.compile("CREATE TABLE (IF NOT EXISTS )?(xp_[a-z_]+)\\s*\\((.*?)\\n\\);", Pattern.DOTALL);
        int tables = 0;
        for (Path p : xamPulse()) {
            String sql = Files.readString(p, StandardCharsets.UTF_8);
            assertFalse(sql.toLowerCase().contains("jsonb"), p + " must not use jsonb");
            assertFalse(Pattern.compile("(?i)drop\\s+table").matcher(sql).find(), p + " must not drop tables");
            Matcher m = create.matcher(sql);
            while (m.find()) {
                tables++;
                assertEquals("IF NOT EXISTS ", m.group(1), m.group(2) + " must use IF NOT EXISTS");
                if (!PARENT_JOINED.contains(m.group(2))) {
                    assertTrue(m.group(3).contains("cloud_account_id"), m.group(2) + " needs cloud_account_id");
                }
            }
            for (String idx : findAll(sql, "CREATE INDEX [^\\n;]*")) {
                assertTrue(idx.contains("IF NOT EXISTS"), idx);
            }
        }
        assertEquals(15, tables, "all XamPulse tables are covered");
    }

    @Test
    void everyCloudAccountIdColumnIsIndexed() throws IOException {
        StringBuilder all = new StringBuilder();
        for (Path p : xamPulse()) {
            all.append(Files.readString(p));
        }
        Matcher t = Pattern.compile("CREATE TABLE IF NOT EXISTS (xp_[a-z_]+)\\s*\\((.*?)\\n\\);", Pattern.DOTALL).matcher(all);
        while (t.find()) {
            if (t.group(2).contains("cloud_account_id") && !t.group(1).equals("xp_account_config")) {
                assertTrue(Pattern.compile("CREATE INDEX IF NOT EXISTS \\w+ ON " + t.group(1) + " \\([^)]*cloud_account_id").matcher(all).find()
                        || t.group(2).contains("UNIQUE"), t.group(1) + " lacks an index on cloud_account_id");
            }
        }
    }

    @Test
    void alertStateKeyAndTabGrantGuard() throws IOException {
        String alerting = Files.readString(DIR.resolve("V20261105_2__xampulse_alerting.sql"));
        assertTrue(alerting.contains("PRIMARY KEY (rule_id, group_key)"));
        String grants = Files.readString(DIR.resolve("V20261105_5__xampulse_tab_access_grants.sql"));
        assertTrue(grants.contains("to_regclass('user_tab_access')"));
        assertTrue(grants.contains("NOT EXISTS"));
        for (String k : List.of("xp-overview", "xp-hosts", "xp-containers", "xp-logs", "xp-metrics", "xp-alerts", "xp-insights", "xp-setup")) {
            assertTrue(grants.contains("'" + k + "'"), k);
        }
        assertTrue(grants.contains("'secops-xamsecure', 'finops-cost'"));
    }

    private static List<String> findAll(String s, String regex) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile(regex).matcher(s);
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }
}
