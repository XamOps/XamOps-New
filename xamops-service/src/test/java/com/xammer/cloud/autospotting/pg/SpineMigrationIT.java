package com.xammer.cloud.autospotting.pg;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code V20261030_2__autospotting_spine.sql} on real PostgreSQL (plan-phase-03.md task 3.1,
 * design-core.md §2.4, §3.2, §4.5, §6.1, §8, §8.1). Every spine table, index and trigger exists on
 * both {@link PgHarness#emptySchema()} and {@link PgHarness#hibernateBase}, and running the
 * migration a second time changes nothing (idempotent).
 */
@Tag("containers")
class SpineMigrationIT {

    private static final String SPINE_VERSION = "20261030.2";

    private static final List<String> TABLES = List.of(
            "spot_group", "spot_group_snapshot", "spot_group_transition", "autospotting_job_lease",
            "spot_operation", "spot_operation_step", "autospotting_switch", "autospotting_allowlist",
            "autospotting_audit", "spot_group_event");

    private static final List<String> INDEXES = List.of(
            "ux_spot_group_identity", "ux_spot_group_stable", "ix_spot_group_reconcile",
            "ix_sgt_group_time", "ix_sgt_group_effective",
            "ux_spot_op_running", "ux_spot_op_idem",
            "ix_asa_group_time", "ix_asa_account_time", "ix_asa_op",
            "ux_sge_dedupe", "ix_sge_group_time");

    private static final List<String> TRIGGERS = List.of(
            "trg_asa_append_only", "trg_sgs_append_only", "trg_sgt_append_only", "trg_sgt_monotonic");

    @Test
    void emptySchema_createsEveryTableIndexAndTrigger() {
        try (PgHarness.Database db = PgHarness.emptySchema()) {
            assertThat(db.appliedVersions()).contains(SPINE_VERSION);
            assertSpine(db.jdbc());
            assertMigrationIsIdempotent(db.jdbc());
        }
    }

    @Test
    void hibernateBase_createsEveryTableIndexAndTrigger() {
        try (PgHarness.Database db = PgHarness.hibernateBase(jdbc -> { })) {
            assertThat(db.appliedVersions()).contains(SPINE_VERSION);
            assertSpine(db.jdbc());
            assertMigrationIsIdempotent(db.jdbc());
        }
    }

    private static void assertSpine(JdbcTemplate jdbc) {
        for (String table : TABLES) {
            assertThat(PgHarness.tableExists(jdbc, table)).as("table %s", table).isTrue();
        }
        for (String index : INDEXES) {
            assertThat(indexExists(jdbc, index)).as("index %s", index).isTrue();
        }
        for (String trigger : TRIGGERS) {
            assertThat(triggerExists(jdbc, trigger)).as("trigger %s", trigger).isTrue();
        }
        // ck_asal_basis is on autospotting_allowlist.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE conname = 'ck_asal_basis'",
                Long.class)).isEqualTo(1L);
        // trg_sgt_monotonic is a BEFORE INSERT trigger; the append-only ones are BEFORE UPDATE OR DELETE.
        assertThat(triggerEvent(jdbc, "trg_sgt_monotonic")).contains("INSERT");
    }

    /** Running the migration a second time by hand changes neither the catalog nor any row. */
    private static void assertMigrationIsIdempotent(JdbcTemplate jdbc) {
        String before = catalogSnapshot(jdbc);
        jdbc.execute(PgHarness.migration(SPINE_VERSION).sql());
        assertThat(catalogSnapshot(jdbc)).isEqualTo(before);
    }

    private static String catalogSnapshot(JdbcTemplate jdbc) {
        return String.join("\n",
                jdbc.queryForList("SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = current_schema() ORDER BY table_name", String.class).toString(),
                jdbc.queryForList("SELECT indexname FROM pg_indexes "
                        + "WHERE schemaname = current_schema() ORDER BY indexname", String.class).toString(),
                jdbc.queryForList("SELECT tgname FROM pg_trigger WHERE NOT tgisinternal ORDER BY tgname",
                        String.class).toString(),
                jdbc.queryForList("SELECT conname || ' ' || pg_get_constraintdef(oid) FROM pg_constraint "
                        + "WHERE conname LIKE 'ck_asal%' ORDER BY conname", String.class).toString());
    }

    private static boolean indexExists(JdbcTemplate jdbc, String name) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT count(*) > 0 FROM pg_indexes WHERE schemaname = current_schema() AND indexname = ?",
                Boolean.class, name));
    }

    private static boolean triggerExists(JdbcTemplate jdbc, String name) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT count(*) > 0 FROM pg_trigger WHERE NOT tgisinternal AND tgname = ?", Boolean.class, name));
    }

    private static String triggerEvent(JdbcTemplate jdbc, String name) {
        // tgtype bit 2 = BEFORE, bit 3 = INSERT, bit 4 = DELETE, bit 5 = UPDATE.
        Integer type = jdbc.queryForObject("SELECT tgtype::int FROM pg_trigger WHERE tgname = ?", Integer.class, name);
        StringBuilder events = new StringBuilder();
        if (type != null && (type & (1 << 2)) != 0) {
            events.append("INSERT ");
        }
        if (type != null && (type & (1 << 3)) != 0) {
            events.append("DELETE ");
        }
        if (type != null && (type & (1 << 4)) != 0) {
            events.append("UPDATE ");
        }
        return events.toString();
    }
}
