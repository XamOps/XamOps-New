package com.xammer.cloud.autospotting.pg;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.SQLException;
import java.util.UUID;

/** Row builders and SQLSTATE helpers shared by the ledger containers tests. */
final class LedgerSql {

    static final String LEDGER_VERSION = "20261108.1";

    /** The ten append-only tables of design-ledger.md §2.4. */
    static final String[] APPEND_ONLY = {"spot_price_snapshot", "savings_bill_import", "savings_bill_fact",
            "savings_ledger_run", "savings_ledger_entry", "savings_ledger_entry_item", "savings_statement_line",
            "savings_statement_event", "savings_ledger_golive", "client_shadow_clearance"};

    /** Every table the migration creates. */
    static final String[] ALL_TABLES = {"spot_usage_interval", "spot_group_capacity_sample",
            "spot_price_snapshot", "savings_ledger_run", "savings_ledger_entry", "savings_ledger_entry_item",
            "savings_statement", "savings_statement_line", "savings_bill_import", "savings_bill_fact",
            "savings_statement_event", "savings_adjustment_request", "savings_dispute", "savings_ledger_golive",
            "savings_billing_hold", "autospotting_contract", "client_shadow_clearance"};

    private LedgerSql() {
    }

    static String sqlState(Runnable action) {
        try {
            action.run();
            return null;
        } catch (DataAccessException e) {
            Throwable t = e;
            while (t != null && !(t instanceof SQLException)) {
                t = t.getCause();
            }
            return t == null ? "UNKNOWN" : ((SQLException) t).getSQLState();
        }
    }

    static String message(Runnable action) {
        try {
            action.run();
            return null;
        } catch (DataAccessException e) {
            return e.getMostSpecificCause().getMessage();
        }
    }

    static void insertSnapshot(JdbcTemplate jdbc, UUID id) {
        jdbc.update("INSERT INTO spot_price_snapshot (id, provider, source, price_kind, region, sku_or_type, rate,"
                + " unit, currency, effective_from, source_version, source_effective_time_stated, fetched_at,"
                + " payload_sha256, payload) VALUES (?, 'AWS', 'AWS_SPOT_HISTORY', 'SPOT', 'us-east-1', 'm5.large',"
                + " 0.0345678901, 'HOUR', 'USD', now(), 'v1', true, now(), ?, CAST('{}' AS jsonb))",
                id, id.toString().replace("-", "") + "0000000000000000000000000000000");
    }

    static void insertRun(JdbcTemplate jdbc, UUID id, UUID supersedes) {
        jdbc.update("INSERT INTO savings_ledger_run (id, provider, cloud_account_id, window_start, window_end,"
                + " calc_version, inputs_sha256, supersedes_run_id, trigger, job_lease_name, lease_generation,"
                + " created_at) VALUES (?, 'AWS', 7, '2026-11-01T00:00:00Z', '2026-11-02T00:00:00Z', 'L1.0.0', 'h',"
                + " ?, 'DAILY', 'ledger-writer', 1, now())", id, supersedes);
    }

    /** An ACCRUAL entry whose columns satisfy every ck_sle_* check; {@code net} is gross minus 0. */
    static void insertEntry(JdbcTemplate jdbc, UUID id, UUID runId, UUID groupId) {
        insertEntry(jdbc, id, runId, groupId, "false", "MEASURED_ONLY", "MEASURED_ONLY", "7.0000000000");
    }

    static void insertEntry(JdbcTemplate jdbc, UUID id, UUID runId, UUID groupId, String billable, String tier,
                            String nonBillableReason, String netSavings) {
        jdbc.update("INSERT INTO savings_ledger_entry (id, run_id, entry_type, provider, cloud_account_id,"
                + " client_id, group_id, period_start, period_end, statement_month, calc_version, tier,"
                + " reconciliation_status, billable, non_billable_reason, currency, instance_seconds,"
                + " counterfactual_unit_seconds, counterfactual_list_cost, effective_rate_adjustment,"
                + " counterfactual_cost, spot_cost, gross_savings, commitment_deduction, net_savings,"
                + " spot_cost_source, price_snapshot_ids, exclusions, inputs, inputs_sha256, created_at)"
                + " VALUES (?, ?, 'ACCRUAL', 'AWS', 7, 3, ?, '2026-11-01T00:00:00Z', '2026-11-02T00:00:00Z',"
                + " '2026-11-01', 'L1.0.0', ?, 'PENDING', " + billable + ", ?, 'USD', 3600, 1.000000,"
                + " 10.0000000000, 0, 10.0000000000, 3.0000000000, 7.0000000000, 0, CAST(? AS numeric),"
                + " 'MEASURED', '{}', CAST('[]' AS jsonb), CAST('{}' AS jsonb), 'h', now())",
                id, runId, groupId, tier, nonBillableReason, netSavings);
    }

    static void insertItem(JdbcTemplate jdbc, UUID entryId) {
        jdbc.update("INSERT INTO savings_ledger_entry_item (entry_id, seq, instance_id, interval_id, instance_type,"
                + " zone, platform, purchase_option, capacity_weight, seconds, billed_seconds,"
                + " counterfactual_unit_seconds, counterfactual_cost, spot_cost, role) VALUES (?, 1, 'i-1', ?,"
                + " 'm5.large', 'us-east-1a', 'LINUX', 'SPOT', 1, 3600, 3600, 1, 10, 3, 'MANAGED_SPOT')",
                entryId, UUID.randomUUID());
    }

    static void insertBillImport(JdbcTemplate jdbc, UUID id) {
        jdbc.update("INSERT INTO savings_bill_import (id, provider, cloud_account_id, source, window_start,"
                + " window_end, query_sha256, source_version, rows_sha256, imported_at) VALUES (?, 'AWS', 7,"
                + " 'AWS_CUR_AGG', now(), now(), 'q', 'v', 'r', now())", id);
    }

    static void insertBillFact(JdbcTemplate jdbc, UUID importId) {
        jdbc.update("INSERT INTO savings_bill_fact (import_id, seq, usage_day, fact_kind, amount, unit, currency)"
                + " VALUES (?, 1, '2026-11-01', 'SPOT_COST', 1.5, 'USD', 'USD')", importId);
    }

    static void insertStatement(JdbcTemplate jdbc, UUID id, String status) {
        jdbc.update("INSERT INTO savings_statement (id, provider, cloud_account_id, client_id, statement_month,"
                + " status, created_at) VALUES (?, 'AWS', 7, 3, '2026-11-01', ?, now())", id, status);
    }

    static void insertStatementLine(JdbcTemplate jdbc, UUID statementId) {
        jdbc.update("INSERT INTO savings_statement_line (statement_id, line_no, line_kind, group_id, group_key,"
                + " tier, billable, instance_hours, counterfactual_cost, spot_cost, commitment_deduction, raw_net,"
                + " billable_amount, currency, detail, entry_ids) VALUES (?, 1, 'GROUP_PERIOD', ?, 'g', 'MEASURED_ONLY',"
                + " false, 1, 10, 3, 0, 7, 7.00, 'USD', CAST('{}' AS jsonb), '{}')", statementId, UUID.randomUUID());
    }

    static void insertStatementEvent(JdbcTemplate jdbc, UUID statementId) {
        jdbc.update("INSERT INTO savings_statement_event (statement_id, from_status, to_status, actor, occurred_at)"
                + " VALUES (?, 'OPEN', 'CLOSED', 'tester', now())", statementId);
    }

    static void insertGoLive(JdbcTemplate jdbc, String provider) {
        jdbc.update("INSERT INTO savings_ledger_golive (provider, go_live_at, set_by, set_at)"
                + " VALUES (?, now(), 'tester', now())", provider);
    }

    static void insertClearance(JdbcTemplate jdbc, long clientId) {
        jdbc.update("INSERT INTO client_shadow_clearance (client_id, provider, cleared_month, evidence, cleared_at,"
                + " cleared_by) VALUES (?, 'AWS', '2026-11-01', CAST('{}' AS jsonb), now(), 'tester')", clientId);
    }

    static void insertContract(JdbcTemplate jdbc, UUID id, long clientId, String fromExpr, String toExpr) {
        jdbc.update("INSERT INTO autospotting_contract (id, client_id, share_pct, monthly_cap, effective_from,"
                + " effective_to, created_by, created_at) VALUES (?, ?, 20.000000, 1000.00, " + fromExpr + ", "
                + toExpr + ", 'tester', now())", id, clientId);
    }
}
