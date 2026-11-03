package com.xammer.cloud.xampulse.service.platform;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Static checks on the shipped ClickHouse DDL: tenancy shape and row-policy coverage. */
class XamPulseClickHouseScriptsTest {

    private static final Pattern CREATE_TABLE = Pattern.compile(
            "CREATE TABLE IF NOT EXISTS (xp_[a-z0-9_]+)\\s*\\((.*?)\\)\\s*ENGINE\\s*=\\s*\\{\\{REPLICATED\\}\\}([A-Za-z]+)(\\([^)]*\\))?(.*?);",
            Pattern.DOTALL);

    private static String script(String version) throws Exception {
        return ClickHouseMigrationRunner.loadClasspathScripts().stream().filter(s -> s.version().equals(version))
                .findFirst().orElseThrow().content();
    }

    private static boolean isLanding(String table) {
        return table.startsWith("xp_land_") || table.equals("xp_schema_version");
    }

    @Test
    void everyNonLandingTableLeadsItsSortKeyWithTenantId() throws Exception {
        int checked = 0;
        for (String v : List.of("V001", "V002", "V004", "V005")) {
            Matcher m = CREATE_TABLE.matcher(script(v));
            while (m.find()) {
                String table = m.group(1);
                if (isLanding(table)) {
                    continue;
                }
                checked++;
                String tail = m.group(5);
                Matcher ord = Pattern.compile("ORDER BY \\(([^)]*)\\)").matcher(tail);
                assertTrue(ord.find(), table + " has no ORDER BY");
                assertTrue(ord.group(1).trim().startsWith("tenant_id"), table + " ORDER BY must start with tenant_id: " + ord.group(1));
                assertTrue(m.group(2).contains("tenant_id UInt64"), table + " must have tenant_id UInt64");
            }
        }
        assertTrue(checked >= 9, "expected the main tables to be checked, got " + checked);
    }

    @Test
    void everyNonLandingTableHasARowPolicyForTheReaderRole() throws Exception {
        Set<String> tables = new java.util.TreeSet<>();
        for (String v : List.of("V001", "V002", "V004", "V005")) {
            Matcher m = CREATE_TABLE.matcher(script(v));
            while (m.find()) {
                if (!isLanding(m.group(1))) {
                    tables.add(m.group(1));
                }
            }
        }
        String policies = script("V007");
        for (String t : tables) {
            assertTrue(Pattern.compile("ROW POLICY IF NOT EXISTS \\w+ ON " + t + "\\s+USING tenant_id = toUInt64OrZero\\(getSetting\\('SQL_xp_tenant'\\)\\) AS permissive TO xp_reader_role")
                    .matcher(policies).find(), "row policy missing for " + t);
        }
        assertEquals(tables.size(), Pattern.compile("CREATE ROW POLICY").matcher(policies).results().count());
    }

    @Test
    void landingTablesAreShortLivedAndNeverGrantedToTheReader() throws Exception {
        String grants = script("V006");
        assertFalse(grants.contains("xp_land_"), "reader/writer must not be granted landing tables");
        for (String v : List.of("V001", "V002")) {
            Matcher m = CREATE_TABLE.matcher(script(v));
            while (m.find()) {
                if (m.group(1).startsWith("xp_land_")) {
                    assertTrue(m.group(5).contains("toIntervalHour(2)"), m.group(1) + " needs a 2 hour TTL");
                }
            }
        }
    }

    @Test
    void materialisedViewsDropRowsWithoutAGatewayStampedTenant() throws Exception {
        for (String v : List.of("V001", "V002")) {
            String s = script(v);
            Matcher m = Pattern.compile("CREATE MATERIALIZED VIEW IF NOT EXISTS (xp_mv_land_[a-z_]+) .*?;", Pattern.DOTALL).matcher(s);
            while (m.find()) {
                assertTrue(m.group(0).contains("WHERE toUInt64OrZero(ResourceAttributes['xp.tenant_id']) != 0"), m.group(1));
            }
        }
    }

    @Test
    void writerCanInsertTheSliceETablesAndReaderCanSelectTheBaselineSource() throws Exception {
        String v006 = script("V006");
        // The reader must be able to read the baselineSamples source.
        assertTrue(v006.contains("GRANT SELECT ON xp_metrics_1h TO xp_reader_role"));
        // V008 confirms the writer's INSERT on the two slice-E targets.
        String v008 = script("V008");
        assertTrue(v008.contains("GRANT INSERT ON xp_baseline TO xp_writer_role"));
        assertTrue(v008.contains("GRANT INSERT ON xp_events TO xp_writer_role"));
    }

    @Test
    void noSecretsOrPasswordsAreInTheScripts() throws Exception {
        String all = ClickHouseMigrationRunner.loadClasspathScripts().stream().map(s -> s.content().toLowerCase()).collect(Collectors.joining("\n"));
        assertFalse(all.contains("password"));
        assertFalse(all.contains("identified by"));
        assertFalse(all.contains("secret"));
    }
}
