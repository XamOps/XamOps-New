package com.xammer.cloud.autospotting.pg;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code V20261110_3__autospotting_gcp_access.sql} on real PostgreSQL, both harness modes (plan-phase-29.md task 29.1):
 * the guarded columns are skipped where {@code cloud_account} does not exist, added where it does, existing rows read
 * {@code KEY} and null, and a second run changes nothing.
 */
@Tag("containers")
class GcpAccessMigrationIT {
    static final String VERSION = "20261110.3";

    @Test
    void emptySchema_skipsTheGuardedColumnsAndIsIdempotent() {
        try (PgHarness.Database db = PgHarness.emptySchema()) {
            assertThat(db.appliedVersions()).contains(VERSION);
            assertThat(PgHarness.tableExists(db.jdbc(), "cloud_account")).isFalse();
            db.jdbc().execute(PgHarness.migration(VERSION).sql());
        }
    }

    @Test
    void hibernateBase_addsTheColumnsExistingRowsReadKeyAndNullAndRerunIsIdempotent() {
        try (PgHarness.Database db = PgHarness.hibernateBase(jdbc -> { })) {
            assertThat(db.appliedVersions()).contains(VERSION);
            assertThat(columnCount(db.jdbc(), "gcp_auth_mode")).isEqualTo(1);
            assertThat(columnCount(db.jdbc(), "gcp_access_type")).isEqualTo(1);
            db.jdbc().execute(PgHarness.migration(VERSION).sql());
            assertThat(columnCount(db.jdbc(), "gcp_auth_mode")).isEqualTo(1);
            assertThat(columnCount(db.jdbc(), "gcp_access_type")).isEqualTo(1);
            String nullable = db.jdbc().queryForObject("SELECT is_nullable FROM information_schema.columns WHERE"
                    + " table_schema = current_schema() AND table_name = 'cloud_account' AND column_name = 'gcp_auth_mode'",
                    String.class);
            assertThat(nullable).isEqualTo("NO");
            String dflt = db.jdbc().queryForObject("SELECT column_default FROM information_schema.columns WHERE"
                    + " table_schema = current_schema() AND table_name = 'cloud_account' AND column_name = 'gcp_auth_mode'",
                    String.class);
            assertThat(dflt).contains("KEY");
        }
    }

    private static int columnCount(JdbcTemplate jdbc, String column) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE"
                + " table_schema = current_schema() AND table_name = 'cloud_account' AND column_name = ?",
                Integer.class, column);
        return n == null ? 0 : n;
    }
}
