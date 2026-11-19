package com.xammer.cloud.autospotting.pg;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code V20261109_1__autospotting_aws_events.sql} on real PostgreSQL, both harness modes (plan-phase-22.md
 * task 22.1): three tables and their indexes exist, the guarded {@code cloud_account.as_forwarder_role_id}
 * column is added where {@code cloud_account} exists and skipped where it does not, and a second run changes
 * nothing.
 */
@Tag("containers")
class AwsEventsMigrationIT {

    static final String VERSION = "20261109.1";
    private static final List<String> TABLES = List.of("autospotting_region_coverage", "autospotting_event_inbox",
            "spot_group_instance");

    @Test
    void emptySchema_createsTablesSkipsTheGuardedColumnAndIsIdempotent() {
        try (PgHarness.Database db = PgHarness.emptySchema()) {
            assertThat(db.appliedVersions()).contains(VERSION);
            assertTables(db.jdbc());
            assertThat(PgHarness.tableExists(db.jdbc(), "cloud_account")).isFalse();
            rerun(db);
            assertTables(db.jdbc());
        }
    }

    @Test
    void hibernateBase_addsTheColumnAndIsIdempotent() {
        try (PgHarness.Database db = PgHarness.hibernateBase(jdbc -> { })) {
            assertThat(db.appliedVersions()).contains(VERSION);
            assertTables(db.jdbc());
            assertThat(columnCount(db.jdbc())).isEqualTo(1);
            rerun(db);
            assertThat(columnCount(db.jdbc())).isEqualTo(1);
        }
    }

    private static int columnCount(JdbcTemplate jdbc) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE"
                + " table_schema = current_schema() AND table_name = 'cloud_account'"
                + " AND column_name = 'as_forwarder_role_id'", Integer.class);
        return n == null ? 0 : n;
    }

    private static void rerun(PgHarness.Database db) {
        String sql = PgHarness.migration(VERSION).sql();
        db.jdbc().execute(sql);
    }

    private static void assertTables(JdbcTemplate jdbc) {
        for (String table : TABLES) {
            assertThat(PgHarness.tableExists(jdbc, table)).as(table).isTrue();
        }
        for (String index : List.of("ix_aei_received", "ix_sgi_group")) {
            Integer n = jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE schemaname = current_schema()"
                    + " AND indexname = ?", Integer.class, index);
            assertThat(n).as(index).isEqualTo(1);
        }
        // No foreign key from or to the new tables (PO-21 retention).
        for (String table : TABLES) {
            Integer fk = jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE contype = 'f'"
                    + " AND (conrelid = to_regclass(?) OR confrelid = to_regclass(?))", Integer.class, table, table);
            assertThat(fk).as("foreign keys on " + table).isZero();
        }
        assertThat(StandardCharsets.UTF_8).isNotNull();
    }
}
