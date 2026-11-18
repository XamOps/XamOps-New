package com.xammer.cloud.autospotting.pg;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Immutability enforced by the database (plan-phase-15.md task 15.2; design-ledger.md §2.4, AC-LED-05):
 * a direct UPDATE and a direct DELETE on each append-only table raise SQLSTATE {@code P0001}; the four
 * guarded tables move only along their legal transitions; a non-USD statement is rejected and a
 * statement inserted without a currency gets USD (GR-05, PD-01).
 */
@Tag("containers")
class LedgerAppendOnlyIT {

    private static final String P0001 = "P0001";

    @Test
    void everyAppendOnlyTable_rejectsUpdateAndDelete() {
        try (PgHarness.Database db = PgHarness.emptySchema()) {
            JdbcTemplate jdbc = db.jdbc();
            UUID snapshot = UUID.randomUUID();
            UUID run = UUID.randomUUID();
            UUID entry = UUID.randomUUID();
            UUID billImport = UUID.randomUUID();
            UUID statement = UUID.randomUUID();
            LedgerSql.insertSnapshot(jdbc, snapshot);
            LedgerSql.insertRun(jdbc, run, null);
            LedgerSql.insertEntry(jdbc, entry, run, UUID.randomUUID());
            LedgerSql.insertItem(jdbc, entry);
            LedgerSql.insertBillImport(jdbc, billImport);
            LedgerSql.insertBillFact(jdbc, billImport);
            LedgerSql.insertStatement(jdbc, statement, "OPEN");
            LedgerSql.insertStatementLine(jdbc, statement);
            LedgerSql.insertStatementEvent(jdbc, statement);
            LedgerSql.insertGoLive(jdbc, "AWS");
            LedgerSql.insertClearance(jdbc, 3L);

            String[][] updates = {
                    {"spot_price_snapshot", "rate = 1"},
                    {"savings_bill_import", "rows_sha256 = 'x'"},
                    {"savings_bill_fact", "amount = 2"},
                    {"savings_ledger_run", "calc_version = 'L9'"},
                    {"savings_ledger_entry", "net_savings = 7.0000000000"},
                    {"savings_ledger_entry_item", "seconds = 1"},
                    {"savings_statement_line", "billable_amount = 1.00"},
                    {"savings_statement_event", "actor = 'x'"},
                    {"savings_ledger_golive", "set_by = 'x'"},
                    {"client_shadow_clearance", "cleared_by = 'x'"}};
            assertThat(updates.length).isEqualTo(LedgerSql.APPEND_ONLY.length);
            for (String[] u : updates) {
                // Even an UPDATE that changes nothing is refused: the trigger fires on the statement's rows.
                assertThat(LedgerSql.sqlState(() -> jdbc.update("UPDATE " + u[0] + " SET " + u[1])))
                        .as("UPDATE " + u[0]).isEqualTo(P0001);
                assertThat(LedgerSql.sqlState(() -> jdbc.update("DELETE FROM " + u[0])))
                        .as("DELETE " + u[0]).isEqualTo(P0001);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM " + u[0], Long.class))
                        .as("rows kept in " + u[0]).isEqualTo(1L);
            }
            assertThat(LedgerSql.message(() -> jdbc.update("DELETE FROM savings_ledger_entry")))
                    .contains("ledger rows are append-only");
        }
    }

    @Test
    void statementCurrency_isUsdOnly_andDefaultsToUsd() {
        try (PgHarness.Database db = PgHarness.emptySchema()) {
            JdbcTemplate jdbc = db.jdbc();
            // Omitted currency gets USD (AC-LED-05).
            UUID defaulted = UUID.randomUUID();
            LedgerSql.insertStatement(jdbc, defaulted, "OPEN");
            assertThat(jdbc.queryForObject("SELECT currency FROM savings_statement WHERE id = ?", String.class,
                    defaulted)).isEqualTo("USD");
            // A non-USD statement violates ck_ss_usd (check_violation).
            assertThat(LedgerSql.sqlState(() -> jdbc.update("INSERT INTO savings_statement (id, provider,"
                    + " cloud_account_id, client_id, statement_month, status, currency, created_at) VALUES (?, 'AWS',"
                    + " 8, 3, '2026-11-01', 'OPEN', 'INR', now())", UUID.randomUUID()))).isEqualTo("23514");
            // An explicit NULL is refused too (not the NULL-tolerant form of the design draft).
            assertThat(LedgerSql.sqlState(() -> jdbc.update("INSERT INTO savings_statement (id, provider,"
                    + " cloud_account_id, client_id, statement_month, status, currency, created_at) VALUES (?, 'AWS',"
                    + " 9, 3, '2026-11-01', 'OPEN', NULL, now())", UUID.randomUUID()))).isEqualTo("23502");
            // A non-USD statement line violates ck_ssl_usd.
            assertThat(LedgerSql.sqlState(() -> jdbc.update("INSERT INTO savings_statement_line (statement_id, line_no,"
                    + " line_kind, group_id, group_key, tier, billable, instance_hours, counterfactual_cost, spot_cost,"
                    + " commitment_deduction, raw_net, billable_amount, currency, detail, entry_ids) VALUES (?, 1,"
                    + " 'GROUP_PERIOD', ?, 'g', 'MEASURED_ONLY', false, 1, 1, 1, 0, 0, 0, 'INR', CAST('{}' AS jsonb),"
                    + " '{}')", defaulted, UUID.randomUUID()))).isEqualTo("23514");
            // The currency of an existing statement cannot be changed either.
            assertThat(LedgerSql.sqlState(() -> jdbc.update("UPDATE savings_statement SET currency = 'INR' WHERE id = ?",
                    defaulted))).isNotNull();
        }
    }

    @Test
    void statementMovesOnlyAlongLegalTransitions() {
        try (PgHarness.Database db = PgHarness.emptySchema()) {
            JdbcTemplate jdbc = db.jdbc();
            UUID s = UUID.randomUUID();
            LedgerSql.insertStatement(jdbc, s, "OPEN");
            // OPEN to INVOICED skips CLOSED: refused.
            assertThat(LedgerSql.sqlState(() -> jdbc.update(
                    "UPDATE savings_statement SET status = 'INVOICED', fee_invoice_id = 5 WHERE id = ?", s)))
                    .isEqualTo(P0001);
            // OPEN to CLOSED with the close columns: allowed.
            jdbc.update("UPDATE savings_statement SET status = 'CLOSED', closed_at = now(), closed_by = 'job',"
                    + " total_billable = 12.34, content_sha256 = 'abc' WHERE id = ?", s);
            // A closed statement keeps its content, and cannot reopen.
            assertThat(LedgerSql.sqlState(() -> jdbc.update(
                    "UPDATE savings_statement SET total_billable = 99.00 WHERE id = ?", s))).isEqualTo(P0001);
            assertThat(LedgerSql.sqlState(() -> jdbc.update(
                    "UPDATE savings_statement SET status = 'OPEN' WHERE id = ?", s))).isEqualTo(P0001);
            // fee_invoice_id moves only with CLOSED to INVOICED.
            assertThat(LedgerSql.sqlState(() -> jdbc.update(
                    "UPDATE savings_statement SET fee_invoice_id = 5 WHERE id = ?", s))).isEqualTo(P0001);
            assertThat(LedgerSql.sqlState(() -> jdbc.update(
                    "UPDATE savings_statement SET status = 'INVOICED' WHERE id = ?", s))).isEqualTo(P0001);
            jdbc.update("UPDATE savings_statement SET status = 'INVOICED', fee_invoice_id = 5 WHERE id = ?", s);
            // INVOICED: repoint to another fee invoice while it stays INVOICED, or void back to CLOSED with null.
            jdbc.update("UPDATE savings_statement SET fee_invoice_id = 6 WHERE id = ?", s);
            assertThat(LedgerSql.sqlState(() -> jdbc.update(
                    "UPDATE savings_statement SET fee_invoice_id = NULL WHERE id = ?", s))).isEqualTo(P0001);
            // A void must drop the fee invoice in the same statement; keeping it is refused.
            assertThat(LedgerSql.sqlState(() -> jdbc.update(
                    "UPDATE savings_statement SET status = 'CLOSED' WHERE id = ?", s))).isEqualTo(P0001);
            assertThat(jdbc.queryForObject("SELECT status FROM savings_statement WHERE id = ?", String.class, s))
                    .isEqualTo("INVOICED");
            jdbc.update("UPDATE savings_statement SET status = 'CLOSED', fee_invoice_id = NULL WHERE id = ?", s);
            assertThat(jdbc.queryForObject("SELECT status FROM savings_statement WHERE id = ?", String.class, s))
                    .isEqualTo("CLOSED");
            // Identity never changes and a statement is never deleted.
            assertThat(LedgerSql.sqlState(() -> jdbc.update(
                    "UPDATE savings_statement SET client_id = 99 WHERE id = ?", s))).isEqualTo(P0001);
            assertThat(LedgerSql.sqlState(() -> jdbc.update("DELETE FROM savings_statement WHERE id = ?", s)))
                    .isEqualTo(P0001);
        }
    }

    @Test
    void adjustmentRequestDisputeAndHold_changeOnlyTheirDecisionColumns() {
        try (PgHarness.Database db = PgHarness.emptySchema()) {
            JdbcTemplate jdbc = db.jdbc();
            UUID req = UUID.randomUUID();
            jdbc.update("INSERT INTO savings_adjustment_request (id, kind, amount, currency, reason, requested_by,"
                    + " requested_at) VALUES (?, 'OPERATOR_CORRECTION', -1.5, 'USD', 'fix', 'a', now())", req);
            assertThat(LedgerSql.sqlState(() -> jdbc.update(
                    "UPDATE savings_adjustment_request SET amount = 9 WHERE id = ?", req))).isEqualTo(P0001);
            jdbc.update("UPDATE savings_adjustment_request SET decision = 'APPROVED', decided_by = 'b',"
                    + " decided_at = now() WHERE id = ?", req);
            assertThat(LedgerSql.sqlState(() -> jdbc.update(
                    "UPDATE savings_adjustment_request SET decision = 'REJECTED' WHERE id = ?", req))).isEqualTo(P0001);
            assertThat(LedgerSql.sqlState(() -> jdbc.update("DELETE FROM savings_adjustment_request"))).isEqualTo(P0001);

            UUID statement = UUID.randomUUID();
            UUID dispute = UUID.randomUUID();
            jdbc.update("INSERT INTO savings_dispute (id, statement_id, line_no, raised_by_user_id, reason, raised_at)"
                    + " VALUES (?, ?, 1, 4, 'why', now())", dispute, statement);
            // One open dispute per line.
            assertThat(LedgerSql.sqlState(() -> jdbc.update("INSERT INTO savings_dispute (id, statement_id, line_no,"
                    + " raised_by_user_id, reason, raised_at) VALUES (?, ?, 1, 5, 'again', now())",
                    UUID.randomUUID(), statement))).isEqualTo("23505");
            assertThat(LedgerSql.sqlState(() -> jdbc.update(
                    "UPDATE savings_dispute SET reason = 'edited' WHERE id = ?", dispute))).isEqualTo(P0001);
            jdbc.update("UPDATE savings_dispute SET status = 'RESOLVED_REJECTED', resolved_by = 'b',"
                    + " resolved_at = now(), resolution_note = 'no' WHERE id = ?", dispute);
            assertThat(LedgerSql.sqlState(() -> jdbc.update(
                    "UPDATE savings_dispute SET status = 'RESOLVED_ACCEPTED' WHERE id = ?", dispute))).isEqualTo(P0001);
            // A resolved dispute frees the line for a new one.
            jdbc.update("INSERT INTO savings_dispute (id, statement_id, line_no, raised_by_user_id, reason, raised_at)"
                    + " VALUES (?, ?, 1, 5, 'again', now())", UUID.randomUUID(), statement);

            UUID hold = UUID.randomUUID();
            jdbc.update("INSERT INTO savings_billing_hold (id, provider, cloud_account_id, statement_month, reason,"
                    + " raised_at) VALUES (?, 'AWS', 7, '2026-11-01', 'VARIANCE_BAND', now())", hold);
            assertThat(LedgerSql.sqlState(() -> jdbc.update(
                    "UPDATE savings_billing_hold SET reason = 'x' WHERE id = ?", hold))).isEqualTo(P0001);
            jdbc.update("UPDATE savings_billing_hold SET resolution = 'checked', resolved_by = 'b',"
                    + " resolved_at = now() WHERE id = ?", hold);
            assertThat(LedgerSql.sqlState(() -> jdbc.update(
                    "UPDATE savings_billing_hold SET resolution = 'again' WHERE id = ?", hold))).isEqualTo(P0001);
            assertThat(LedgerSql.sqlState(() -> jdbc.update("DELETE FROM savings_billing_hold"))).isEqualTo(P0001);
        }
    }

    @Test
    void entryChecksAndRunChain_rejectWrongMoney() {
        try (PgHarness.Database db = PgHarness.emptySchema()) {
            JdbcTemplate jdbc = db.jdbc();
            UUID run = UUID.randomUUID();
            UUID group = UUID.randomUUID();
            LedgerSql.insertRun(jdbc, run, null);
            // A second root for the same window forks the chain (ux_slr_root); a second successor too.
            assertThat(LedgerSql.sqlState(() -> LedgerSql.insertRun(jdbc, UUID.randomUUID(), null))).isEqualTo("23505");
            UUID next = UUID.randomUUID();
            LedgerSql.insertRun(jdbc, next, run);
            assertThat(LedgerSql.sqlState(() -> LedgerSql.insertRun(jdbc, UUID.randomUUID(), run))).isEqualTo("23505");

            UUID entry = UUID.randomUUID();
            LedgerSql.insertEntry(jdbc, entry, run, group);
            // One entry per run and group.
            assertThat(LedgerSql.sqlState(() -> LedgerSql.insertEntry(jdbc, UUID.randomUUID(), run, group)))
                    .isEqualTo("23505");
            // net must equal gross minus the deduction (ck_sle_math).
            assertThat(LedgerSql.sqlState(() -> LedgerSql.insertEntry(jdbc, UUID.randomUUID(), next, group, "false",
                    "MEASURED_ONLY", "MEASURED_ONLY", "6.9999999999"))).isEqualTo("23514");
            // A billable entry must be verified (ck_sle_tier) and a non-billable one needs a reason (ck_sle_reason).
            assertThat(LedgerSql.sqlState(() -> LedgerSql.insertEntry(jdbc, UUID.randomUUID(), next, group, "true",
                    "MEASURED_ONLY", "MEASURED_ONLY", "7.0000000000"))).isEqualTo("23514");
            assertThat(LedgerSql.sqlState(() -> jdbc.update("UPDATE savings_ledger_entry SET net_savings = 1"))).isEqualTo(P0001);
            // A negative net is stored raw: only the statement line clamps it (PO-26).
            jdbc.update("INSERT INTO savings_ledger_entry (id, run_id, entry_type, provider, cloud_account_id,"
                    + " client_id, group_id, period_start, period_end, statement_month, calc_version, tier,"
                    + " reconciliation_status, billable, non_billable_reason, currency, instance_seconds,"
                    + " counterfactual_unit_seconds, counterfactual_list_cost, effective_rate_adjustment,"
                    + " counterfactual_cost, spot_cost, gross_savings, commitment_deduction, net_savings,"
                    + " spot_cost_source, price_snapshot_ids, exclusions, inputs, inputs_sha256, created_at)"
                    + " VALUES (?, ?, 'ACCRUAL', 'AWS', 7, 3, ?, '2026-11-02T00:00:00Z', '2026-11-03T00:00:00Z',"
                    + " '2026-11-01', 'L1.0.0', 'BILL_VERIFIED', 'RECONCILED', true, NULL, 'USD', 3600, 1, 2, 0, 2, 5,"
                    + " -3, 0, -3, 'BILL', '{}', CAST('[]' AS jsonb), CAST('{}' AS jsonb), 'h', now())",
                    UUID.randomUUID(), next, group);
            // The statement line, unlike the entry, never bills a negative amount (ck_ssl_nonneg).
            UUID statement = UUID.randomUUID();
            LedgerSql.insertStatement(jdbc, statement, "OPEN");
            assertThat(LedgerSql.sqlState(() -> jdbc.update("INSERT INTO savings_statement_line (statement_id, line_no,"
                    + " line_kind, group_id, group_key, tier, billable, instance_hours, counterfactual_cost, spot_cost,"
                    + " commitment_deduction, raw_net, billable_amount, currency, detail, entry_ids) VALUES (?, 1,"
                    + " 'GROUP_PERIOD', ?, 'g', 'BILL_VERIFIED', true, 1, 2, 5, 0, -3, -3.00, 'USD',"
                    + " CAST('{}' AS jsonb), '{}')", statement, group))).isEqualTo("23514");
        }
    }
}
