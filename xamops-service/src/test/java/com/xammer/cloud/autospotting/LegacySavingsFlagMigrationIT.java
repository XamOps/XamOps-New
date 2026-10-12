package com.xammer.cloud.autospotting;

import com.xammer.cloud.autospotting.pg.PgHarness;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code V20261009_1__autospotting_legacy_savings_flag.sql} on real PostgreSQL (FR-LED-20, D-12,
 * ledger §12.2 "B1 exit test"), plus the vmss_spot_config and Azure-row checks for Phase A's
 * {@code V20261006_1__remove_azure_autospotting.sql} (DC-04, AC-ROL-04).
 *
 * <p>Three starting points: an empty database; the prod shape (ddl-auto made daily_spot_savings
 * without the three columns, with rows, and Azure data is still present); and a deploy where
 * ddl-auto=update added the columns before Flyway ran.
 */
@Tag("containers")
class LegacySavingsFlagMigrationIT {

    private static final String LEGACY_FLAG_VERSION = "20261009.1";

    private static final long CLIENT = 1L;
    private static final long AWS = 101L;
    private static final long GCP = 102L;
    private static final long AZURE = 103L;

    // ── empty schema ─────────────────────────────────────────────────────────

    @Test
    void emptySchema_createsTheTableWithTheLegacyFlags() {
        try (PgHarness.Database db = PgHarness.emptySchema()) {
            JdbcTemplate jdbc = db.jdbc();
            assertThat(db.appliedVersions()).contains("20261006.1", LEGACY_FLAG_VERSION);

            assertThat(PgHarness.tableExists(jdbc, "daily_spot_savings")).isTrue();
            assertThat(PgHarness.tableExists(jdbc, "daily_savings_id_seq")).isTrue();
            assertThat(uniqueColumns(jdbc)).contains("account_id,date");
            assertFlagColumns(jdbc);
            assertCheckConstraint(jdbc);

            assertInsertWithoutTheColumnsGetsTheDefaults(jdbc, 42L);
            assertNotLegacyOrBillableIsRejected(jdbc);
            assertRunningTheMigrationAgainChangesNothing(jdbc);

            assertVmssSpotConfigIsGone(jdbc);
            assertNoRowReferencesAnAzureAccount(jdbc);
        }
    }

    // ── prod shape: ddl-auto table without the columns, rows, Azure data ────────

    @Test
    void hibernateBase_marksEveryExistingRowLegacyAndPhaseARemovedAzure() {
        try (PgHarness.Database db = PgHarness.hibernateBase(jdbc -> {
            // ddl-auto made the table from the entity as it was before this change.
            jdbc.execute("ALTER TABLE daily_spot_savings DROP COLUMN legacy, DROP COLUMN billable, DROP COLUMN source");
            insertAccountsAndAzureData(jdbc);
            jdbc.update("INSERT INTO daily_spot_savings (id, account_id, date, amount_saved, swap_count) VALUES "
                    + "(1, ?, DATE '2026-09-01', 12.5, 3), (2, ?, DATE '2026-09-01', 4.25, 1), "
                    + "(3, ?, DATE '2026-09-02', 7.0, 2)", AWS, GCP, AZURE);
        })) {
            JdbcTemplate jdbc = db.jdbc();
            assertThat(db.baselineVersion()).contains(PgHarness.BASELINE_VERSION);
            assertThat(db.appliedVersions()).containsSubsequence("20261006.1", LEGACY_FLAG_VERSION);

            // Phase A deleted the Azure row; every remaining row is now legacy and not billable.
            assertThat(jdbc.queryForList("SELECT id, legacy, billable, source, amount_saved FROM daily_spot_savings "
                    + "ORDER BY id")).containsExactly(
                    Map.of("id", 1L, "legacy", true, "billable", false, "source", "LEGACY", "amount_saved", 12.5),
                    Map.of("id", 2L, "legacy", true, "billable", false, "source", "LEGACY", "amount_saved", 4.25));
            assertFlagColumns(jdbc);
            assertCheckConstraint(jdbc);

            assertInsertWithoutTheColumnsGetsTheDefaults(jdbc, AWS);
            assertNotLegacyOrBillableIsRejected(jdbc);
            assertRunningTheMigrationAgainChangesNothing(jdbc);

            assertVmssSpotConfigIsGone(jdbc);
            assertNoRowReferencesAnAzureAccount(jdbc);
            // The AWS and GCP rows of the other shared tables survive.
            assertThat(jdbc.queryForList("SELECT id FROM spot_optimization_event ORDER BY id", Long.class))
                    .containsExactly(11L);
            assertThat(jdbc.queryForList("SELECT account_id FROM autospotting_settings ORDER BY 1", Long.class))
                    .containsExactly(AWS);
        }
    }

    // ── ddl-auto=update added the columns before Flyway ran ─────────────────────

    @Test
    void hibernateBase_whenDdlAutoAddedTheColumnsFirst_rowsAreStillLegacy() {
        try (PgHarness.Database db = PgHarness.hibernateBase(jdbc -> {
            insertAccountsAndAzureData(jdbc);
            // Old-code style insert: the three columns are not named.
            jdbc.update("INSERT INTO daily_spot_savings (id, account_id, date, amount_saved, swap_count) "
                    + "VALUES (1, ?, DATE '2026-09-01', 9.0, 1)", AWS);
        })) {
            JdbcTemplate jdbc = db.jdbc();
            assertThat(db.appliedVersions()).containsSubsequence("20261006.1", LEGACY_FLAG_VERSION);

            assertThat(jdbc.queryForMap("SELECT legacy, billable, source FROM daily_spot_savings WHERE id = 1"))
                    .isEqualTo(Map.of("legacy", true, "billable", false, "source", "LEGACY"));
            assertFlagColumns(jdbc);
            assertCheckConstraint(jdbc);
            assertNotLegacyOrBillableIsRejected(jdbc);
            assertRunningTheMigrationAgainChangesNothing(jdbc);
            assertVmssSpotConfigIsGone(jdbc);
            assertNoRowReferencesAnAzureAccount(jdbc);
        }
    }

    // ── fixture ──────────────────────────────────────────────────────────────

    /**
     * One client, an AWS, a GCP and an Azure account, Azure and AWS rows in the shared AutoSpotting
     * tables, and the Azure feature's own table with its Hibernate sequence, all before Phase A's
     * migration runs.
     */
    private static void insertAccountsAndAzureData(JdbcTemplate jdbc) {
        PgHarness.insertRow(jdbc, "client", Map.of("id", CLIENT, "name", "fixture-client"));
        PgHarness.insertRow(jdbc, "cloud_account", Map.of("id", AWS, "account_name", "aws-fixture",
                "provider", "AWS", "status", "CONNECTED", "client_id", CLIENT, "aws_account_id", "012345678901"));
        PgHarness.insertRow(jdbc, "cloud_account", Map.of("id", GCP, "account_name", "gcp-fixture",
                "provider", "GCP", "status", "CONNECTED", "client_id", CLIENT, "gcp_project_id", "fixture-project"));
        PgHarness.insertRow(jdbc, "cloud_account", Map.of("id", AZURE, "account_name", "azure-fixture",
                "provider", "Azure", "status", "CONNECTED", "client_id", CLIENT));

        for (long[] event : new long[][] {{11L, AWS}, {12L, AZURE}}) {
            PgHarness.insertRow(jdbc, "spot_optimization_event", Map.of("id", event[0], "account_id", event[1],
                    "asg_name", "group-" + event[0], "region", "us-east-1", "status", "SUCCESS",
                    "event_time", java.sql.Timestamp.valueOf("2026-09-01 10:00:00")));
        }
        PgHarness.insertRow(jdbc, "autospotting_settings", Map.of("account_id", AWS, "enabled", true));
        PgHarness.insertRow(jdbc, "autospotting_settings", Map.of("account_id", AZURE, "enabled", true));

        // vmss_spot_config's entity is gone, so ddl-auto no longer makes it; prod still has it.
        jdbc.execute("CREATE SEQUENCE vmss_spot_config_id_seq START WITH 1 INCREMENT BY 1");
        jdbc.execute("CREATE TABLE vmss_spot_config (id BIGINT PRIMARY KEY, "
                + "account_id BIGINT NOT NULL REFERENCES cloud_account(id), resource_group VARCHAR(255) NOT NULL, "
                + "vmss_name VARCHAR(255) NOT NULL, enabled BOOLEAN NOT NULL DEFAULT FALSE)");
        jdbc.update("INSERT INTO vmss_spot_config (id, account_id, resource_group, vmss_name) "
                + "VALUES (nextval('vmss_spot_config_id_seq'), ?, 'rg-fixture', 'vmss-fixture')", AZURE);
    }

    // ── assertions ───────────────────────────────────────────────────────────

    private static void assertFlagColumns(JdbcTemplate jdbc) {
        assertThat(jdbc.queryForList("SELECT column_name, data_type, character_maximum_length, is_nullable, "
                + "column_default FROM information_schema.columns WHERE table_schema = current_schema() "
                + "AND table_name = 'daily_spot_savings' AND column_name IN ('legacy', 'billable', 'source') "
                + "ORDER BY column_name"))
                .extracting(c -> c.get("column_name") + " " + c.get("data_type") + " "
                        + c.get("character_maximum_length") + " " + c.get("is_nullable") + " " + c.get("column_default"))
                .containsExactly(
                        "billable boolean null NO false",
                        "legacy boolean null NO true",
                        "source character varying 16 NO 'LEGACY'::character varying");
    }

    private static void assertCheckConstraint(JdbcTemplate jdbc) {
        assertThat(jdbc.queryForList("SELECT pg_get_constraintdef(oid) || ' validated=' || convalidated "
                + "FROM pg_constraint WHERE conname = 'ck_dss_legacy'", String.class))
                .containsExactly("CHECK ((legacy AND (NOT billable))) validated=true");
        assertThat(jdbc.queryForObject("SELECT conrelid::regclass::text FROM pg_constraint "
                + "WHERE conname = 'ck_dss_legacy'", String.class)).isEqualTo("daily_spot_savings");
    }

    private static void assertInsertWithoutTheColumnsGetsTheDefaults(JdbcTemplate jdbc, long accountId) {
        jdbc.update("INSERT INTO daily_spot_savings (id, account_id, date, amount_saved, swap_count) "
                + "VALUES (900, ?, DATE '2026-10-01', 3.5, 1)", accountId);
        assertThat(jdbc.queryForMap("SELECT legacy, billable, source FROM daily_spot_savings WHERE id = 900"))
                .isEqualTo(Map.of("legacy", true, "billable", false, "source", "LEGACY"));
    }

    private static void assertNotLegacyOrBillableIsRejected(JdbcTemplate jdbc) {
        assertThatThrownBy(() -> jdbc.update("UPDATE daily_spot_savings SET billable = true"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_dss_legacy");
        assertThatThrownBy(() -> jdbc.update("UPDATE daily_spot_savings SET legacy = false"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_dss_legacy");
    }

    private static void assertRunningTheMigrationAgainChangesNothing(JdbcTemplate jdbc) {
        String before = snapshot(jdbc);
        jdbc.execute(PgHarness.migration(LEGACY_FLAG_VERSION).sql());
        assertThat(snapshot(jdbc)).isEqualTo(before);
    }

    private static String snapshot(JdbcTemplate jdbc) {
        return String.join("\n",
                jdbc.queryForList("SELECT * FROM daily_spot_savings ORDER BY id").toString(),
                jdbc.queryForList("SELECT column_name, data_type, is_nullable, column_default "
                        + "FROM information_schema.columns WHERE table_schema = current_schema() "
                        + "AND table_name = 'daily_spot_savings' ORDER BY ordinal_position").toString(),
                jdbc.queryForList("SELECT conname, pg_get_constraintdef(oid), convalidated FROM pg_constraint "
                        + "WHERE conrelid = 'daily_spot_savings'::regclass ORDER BY conname").toString());
    }

    private static List<String> uniqueColumns(JdbcTemplate jdbc) {
        return jdbc.queryForList("SELECT string_agg(a.attname::text, ',' ORDER BY k.ord) FROM pg_constraint c "
                + "CROSS JOIN LATERAL unnest(c.conkey) WITH ORDINALITY AS k(attnum, ord) "
                + "JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.attnum "
                + "WHERE c.conrelid = 'daily_spot_savings'::regclass AND c.contype = 'u' GROUP BY c.oid", String.class);
    }

    private static void assertVmssSpotConfigIsGone(JdbcTemplate jdbc) {
        assertThat(jdbc.queryForObject("SELECT to_regclass('vmss_spot_config') IS NULL", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT to_regclass('vmss_spot_config_id_seq') IS NULL", Boolean.class)).isTrue();
    }

    private static void assertNoRowReferencesAnAzureAccount(JdbcTemplate jdbc) {
        for (String table : List.of("daily_spot_savings", "spot_optimization_event", "autospotting_settings")) {
            if (!PgHarness.tableExists(jdbc, table)) {
                continue; // empty schema: Hibernate never made it, so nothing can reference Azure
            }
            long azureRows = PgHarness.tableExists(jdbc, "cloud_account")
                    ? jdbc.queryForObject("SELECT count(*) FROM " + table + " x JOIN cloud_account ca "
                            + "ON ca.id = x.account_id WHERE upper(btrim(ca.provider)) = 'AZURE'", Long.class)
                    : 0L;
            assertThat(azureRows).as("Azure rows in %s", table).isZero();
        }
    }
}
