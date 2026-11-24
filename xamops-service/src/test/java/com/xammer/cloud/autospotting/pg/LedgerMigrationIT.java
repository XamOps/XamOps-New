package com.xammer.cloud.autospotting.pg;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code V20261108_1__autospotting_ledger.sql} on real PostgreSQL, both harness modes (plan-phase-15.md
 * task 15.1; design-ledger.md §2): every table, index, named constraint and trigger exists; no foreign
 * key and no floating-point or timezone-less column; the statement currency is NOT NULL with a USD
 * check (GR-05, PD-01); and running the migration a second time changes nothing.
 */
@Tag("containers")
class LedgerMigrationIT {

    private static final List<String> INDEXES = List.of("ux_sps_dedupe", "ix_sps_lookup", "ux_slr_root",
            "ux_slr_chain", "ux_sle_run_group", "ix_sle_account_month", "ix_sle_group_period", "ix_sle_adjref",
            "ix_slei_instance", "ux_ss_period", "ux_sd_open_line", "ux_sui_instance", "ix_sui_group_start");

    private static final List<String> CONSTRAINTS = List.of("ck_sle_type", "ck_sle_tier", "ck_sle_unknown",
            "ck_sle_reason", "ck_sle_math", "ck_ss_usd", "ck_ssl_nonneg", "ck_ssl_usd", "ck_asc_share",
            "ck_asc_cap", "ck_asc_capcur", "ck_asc_discount", "ck_asc_range");

    @Test
    void emptySchema_createsEverythingAndIsIdempotent() {
        try (PgHarness.Database db = PgHarness.emptySchema()) {
            assertThat(db.appliedVersions()).contains(LedgerSql.LEDGER_VERSION);
            assertComplete(db.jdbc());
            assertIdempotent(db.jdbc());
        }
    }

    @Test
    void hibernateBase_createsEverythingAndIsIdempotent() {
        try (PgHarness.Database db = PgHarness.hibernateBase(jdbc -> { })) {
            assertThat(db.appliedVersions()).contains(LedgerSql.LEDGER_VERSION);
            assertComplete(db.jdbc());
            assertIdempotent(db.jdbc());
        }
    }

    private static void assertComplete(JdbcTemplate jdbc) {
        for (String table : LedgerSql.ALL_TABLES) {
            assertThat(PgHarness.tableExists(jdbc, table)).as("table " + table).isTrue();
        }
        for (String index : INDEXES) {
            assertThat(count(jdbc, "SELECT count(*) FROM pg_indexes WHERE schemaname = current_schema()"
                    + " AND indexname = ?", index)).as("index " + index).isEqualTo(1);
        }
        for (String constraint : CONSTRAINTS) {
            assertThat(count(jdbc, "SELECT count(*) FROM pg_constraint WHERE conname = ?", constraint))
                    .as("constraint " + constraint).isEqualTo(1);
        }
        // Append-only triggers on ten tables, guarded-transition triggers on four, contract triggers on one.
        for (String table : LedgerSql.APPEND_ONLY) {
            assertThat(count(jdbc, "SELECT count(*) FROM pg_trigger WHERE tgname = ? AND NOT tgisinternal",
                    "trg_" + table + "_append_only")).as("append-only trigger " + table).isEqualTo(1);
        }
        for (String table : List.of("savings_statement", "savings_adjustment_request", "savings_dispute",
                "savings_billing_hold")) {
            assertThat(count(jdbc, "SELECT count(*) FROM pg_trigger WHERE tgname = ? AND NOT tgisinternal",
                    "trg_" + table + "_guard")).as("guard trigger " + table).isEqualTo(1);
        }
        assertThat(count(jdbc, "SELECT count(*) FROM pg_trigger WHERE tgname IN ('trg_autospotting_contract_guard',"
                + " 'trg_autospotting_contract_no_delete') AND NOT tgisinternal")).isEqualTo(2);
        // The contract guard takes the per-client advisory lock (design-ledger.md §2.3).
        assertThat(jdbc.queryForObject("SELECT prosrc FROM pg_proc WHERE proname = 'autospotting_contract_guard'",
                String.class)).contains("pg_advisory_xact_lock(NEW.client_id)");
        // No foreign key from or to any ledger table (FR-LED-31).
        for (String table : LedgerSql.ALL_TABLES) {
            assertThat(count(jdbc, "SELECT count(*) FROM pg_constraint WHERE contype = 'f'"
                    + " AND (conrelid = to_regclass(?) OR confrelid = to_regclass(?))", table, table))
                    .as("foreign keys on " + table).isZero();
        }
        // Money and rates are NUMERIC, never floating point; instants are TIMESTAMPTZ (FR-LED-17, NFR-07).
        for (String table : LedgerSql.ALL_TABLES) {
            List<Map<String, Object>> columns = jdbc.queryForList("SELECT column_name, data_type FROM"
                    + " information_schema.columns WHERE table_schema = current_schema() AND table_name = ?", table);
            assertThat(columns).as("columns of " + table).isNotEmpty();
            for (Map<String, Object> column : columns) {
                String type = (String) column.get("data_type");
                assertThat(type).as(table + "." + column.get("column_name")).isNotIn("double precision", "real",
                        "money", "timestamp without time zone");
            }
        }
        // GR-05: the statement currency is NOT NULL, defaults to USD, and the check names USD only.
        assertThat(jdbc.queryForObject("SELECT is_nullable FROM information_schema.columns WHERE table_schema ="
                + " current_schema() AND table_name = 'savings_statement' AND column_name = 'currency'",
                String.class)).isEqualTo("NO");
        assertThat(jdbc.queryForObject("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'ck_ss_usd'",
                String.class)).contains("USD").doesNotContain("IS NULL");
        // The fee objects ship in Phase 34, not here.
        assertThat(PgHarness.tableExists(jdbc, "autospotting_fee_invoice")).isFalse();
    }

    private static void assertIdempotent(JdbcTemplate jdbc) {
        String before = snapshot(jdbc);
        jdbc.execute(PgHarness.migration(LedgerSql.LEDGER_VERSION).sql());
        assertThat(snapshot(jdbc)).isEqualTo(before);
        jdbc.execute(PgHarness.migration(LedgerSql.LEDGER_VERSION).sql());
        assertThat(snapshot(jdbc)).isEqualTo(before);
    }

    private static String snapshot(JdbcTemplate jdbc) {
        return jdbc.queryForList("SELECT 'idx:' || indexname FROM pg_indexes WHERE schemaname = current_schema()"
                        + " UNION ALL SELECT 'con:' || conname || ':' || pg_get_constraintdef(oid) FROM pg_constraint"
                        + " WHERE connamespace = current_schema()::regnamespace"
                        + " UNION ALL SELECT 'trg:' || tgname FROM pg_trigger WHERE NOT tgisinternal"
                        + " UNION ALL SELECT 'fn:' || proname || md5(prosrc) FROM pg_proc WHERE proname LIKE 'autospotting%'"
                        + " UNION ALL SELECT 'col:' || table_name || '.' || column_name || ':' || data_type || ':'"
                        + " || coalesce(column_default, '') || ':' || is_nullable FROM information_schema.columns"
                        + " WHERE table_schema = current_schema()"
                        + " ORDER BY 1", String.class).toString();
    }

    private static long count(JdbcTemplate jdbc, String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }
}
