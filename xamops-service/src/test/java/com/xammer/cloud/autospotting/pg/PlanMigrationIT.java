package com.xammer.cloud.autospotting.pg;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code V20261031_1__autospotting_plan.sql} on real PostgreSQL (plan-phase-11.md task 11.1,
 * design-core.md §7.3, §7.5, §3.7, §11). {@code spot_plan} with {@code ux_spot_plan_open},
 * {@code ux_spot_plan_idem} and {@code batch_id} is created on both harness modes, and the
 * {@code user_cloud_account_access.autospotting_write} column is added (guarded). Running the
 * migration a second time changes nothing.
 */
@Tag("containers")
class PlanMigrationIT {

    private static final String PLAN_VERSION = "20261031.1";

    @Test
    void emptySchema_createsSpotPlanAndIndexes() {
        try (PgHarness.Database db = PgHarness.emptySchema()) {
            assertThat(db.appliedVersions()).contains(PLAN_VERSION);
            JdbcTemplate jdbc = db.jdbc();
            assertThat(PgHarness.tableExists(jdbc, "spot_plan")).isTrue();
            assertThat(indexExists(jdbc, "ux_spot_plan_open")).isTrue();
            assertThat(indexExists(jdbc, "ux_spot_plan_idem")).isTrue();
            assertThat(indexExists(jdbc, "ix_spot_plan_open_expiry")).isTrue();
            assertThat(indexExists(jdbc, "ix_spot_plan_batch")).isTrue();
            assertThat(columnExists(jdbc, "spot_plan", "batch_id")).isTrue();
            // user_cloud_account_access does not exist in the empty AutoSpotting-only schema, so the
            // guarded ADD COLUMN is a no-op there (to_regclass guard): the migration still applies.
            assertThat(PgHarness.tableExists(jdbc, "user_cloud_account_access")).isFalse();
            assertIdempotent(jdbc);
        }
    }

    @Test
    void hibernateBase_createsSpotPlanAndAddsAutospottingWriteColumn() {
        try (PgHarness.Database db = PgHarness.hibernateBase(jdbc -> { })) {
            assertThat(db.appliedVersions()).contains(PLAN_VERSION);
            JdbcTemplate jdbc = db.jdbc();
            assertThat(PgHarness.tableExists(jdbc, "spot_plan")).isTrue();
            assertThat(indexExists(jdbc, "ux_spot_plan_open")).isTrue();
            // Hibernate builds user_cloud_account_access from the entity (which now carries the column).
            assertThat(PgHarness.tableExists(jdbc, "user_cloud_account_access")).isTrue();
            assertThat(columnExists(jdbc, "user_cloud_account_access", "autospotting_write")).isTrue();
            assertIdempotent(jdbc);
        }
    }

    @Test
    void openPlanIndexAllowsOneOpenPerGroup() {
        try (PgHarness.Database db = PgHarness.emptySchema()) {
            JdbcTemplate jdbc = db.jdbc();
            java.util.UUID group = java.util.UUID.randomUUID();
            insertOpenPlan(jdbc, group);
            // A second OPEN plan for the same group violates ux_spot_plan_open.
            assertThat(catchInsert(() -> insertOpenPlan(jdbc, group))).isTrue();
            // A CONFIRMED plan for the same group is allowed (the partial index is WHERE status='OPEN').
            insertPlan(jdbc, group, "CONFIRMED");
        }
    }

    private static void insertOpenPlan(JdbcTemplate jdbc, java.util.UUID group) {
        insertPlan(jdbc, group, "OPEN");
    }

    private static void insertPlan(JdbcTemplate jdbc, java.util.UUID group, String status) {
        jdbc.update("INSERT INTO spot_plan (id, group_id, cloud_account_id, kind, status, from_state, version,"
                + " schema_version, live_fingerprint, body, requires_full_spot_optin, warning_ids, dry_run,"
                + " created_by, created_at, expires_at)"
                + " VALUES (?, ?, 1, 'ENABLE', ?, 'UNMANAGED', 1, 1, 'fp', CAST('{}' AS jsonb), false,"
                + " CAST('[]' AS jsonb), false, 'tester', now(), now() + interval '15 minutes')",
                java.util.UUID.randomUUID(), group, status);
    }

    private static boolean catchInsert(Runnable r) {
        try {
            r.run();
            return false;
        } catch (RuntimeException e) {
            return true; // unique-violation
        }
    }

    private static void assertIdempotent(JdbcTemplate jdbc) {
        String before = snapshot(jdbc);
        jdbc.execute(PgHarness.migration(PLAN_VERSION).sql());
        assertThat(snapshot(jdbc)).isEqualTo(before);
    }

    private static String snapshot(JdbcTemplate jdbc) {
        return jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE schemaname = current_schema()"
                + " AND tablename = 'spot_plan' ORDER BY indexname", String.class).toString();
    }

    private static boolean indexExists(JdbcTemplate jdbc, String name) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT count(*) > 0 FROM pg_indexes WHERE schemaname = current_schema() AND indexname = ?",
                Boolean.class, name));
    }

    private static boolean columnExists(JdbcTemplate jdbc, String table, String column) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT count(*) > 0 FROM information_schema.columns WHERE table_schema = current_schema()"
                        + " AND table_name = ? AND column_name = ?", Boolean.class, table, column));
    }
}
