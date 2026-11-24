package com.xammer.cloud.autospotting.pg;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.service.autospotting.ledger.read.JdbcSavingsLedgerReadService;
import com.xammer.cloud.service.autospotting.ledger.read.SavingsLedgerReadService.ClientSpotSavings;
import com.xammer.cloud.service.autospotting.ledger.read.SavingsLedgerReadService.SavingsSummary;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.YearMonth;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** AC-LED-20 against PostgreSQL: shadow vs invoiceable vs open, one current run per window. */
@Tag("containers")
class SavingsLedgerReadServiceIT {

    private static void statement(JdbcTemplate jdbc, String month, String status, Boolean invoiceable, String total,
                                  String closedAt) {
        jdbc.update("INSERT INTO savings_statement (id, provider, cloud_account_id, client_id, statement_month, status,"
                + " invoiceable, total_billable, closed_at, created_at) VALUES (?, 'AWS', 7, 3, CAST(? AS date), ?, ?,"
                + " CAST(? AS numeric), CAST(? AS timestamptz), now())", UUID.randomUUID(), month, status, invoiceable,
                total, closedAt);
    }

    @Test
    void shadowAddsToNotInvoicedOnly_invoiceableToRealized_openMonthToAccruing_supersededRunNotCounted() {
        try (PgHarness.Database db = PgHarness.emptySchema()) {
            JdbcTemplate jdbc = db.jdbc();
            statement(jdbc, "2026-08-01", "CLOSED", true, "244.48", "2026-09-05T08:00:00Z");
            statement(jdbc, "2026-09-01", "CLOSED", false, "50.00", "2026-10-05T08:00:00Z");
            LedgerSql.insertStatement(jdbc, UUID.randomUUID(), "OPEN"); // 2026-11, account 7, client 3
            UUID run1 = UUID.randomUUID();
            UUID run2 = UUID.randomUUID();
            UUID group = UUID.randomUUID();
            LedgerSql.insertRun(jdbc, run1, null);
            LedgerSql.insertRun(jdbc, run2, run1);
            LedgerSql.insertEntry(jdbc, UUID.randomUUID(), run1, group, "false", "MEASURED_ONLY", "NOT_RECONCILED", "7.0000000000");
            LedgerSql.insertEntry(jdbc, UUID.randomUUID(), run2, group, "false", "MEASURED_ONLY", "NOT_RECONCILED", "7.0000000000");

            JdbcSavingsLedgerReadService service = new JdbcSavingsLedgerReadService(Ledger17Support.dataSource(db));
            ClientSpotSavings c = service.getClientSpotSavings(3L, YearMonth.of(2026, 8), YearMonth.of(2026, 11));
            var aws = c.provider("AWS");
            assertThat(aws.realized().amount()).isEqualByComparingTo("244.48");
            assertThat(aws.shadowNotInvoiced().amount()).isEqualByComparingTo("50.00");
            assertThat(aws.accruing().amount()).isEqualByComparingTo("7");
            assertThat(c.realizedMonths()).containsExactly(YearMonth.of(2026, 8));
            assertThat(c.provider("GCP").realized().amount()).isEqualByComparingTo("0");
            assertThat(c.daily()).hasSize(1);
            assertThat(c.daily().get(0).net().amount()).isEqualByComparingTo("7");

            CloudAccount account = new CloudAccount();
            account.setId(7L);
            account.setProvider("AWS");
            SavingsSummary s = service.getSavingsSummary(account, Instant.parse("2026-08-01T00:00:00Z"),
                    Instant.parse("2026-12-01T00:00:00Z"));
            assertThat(s.billableSavings().amount()).isEqualByComparingTo("244.48");
            assertThat(s.shadowNotInvoiced().amount()).isEqualByComparingTo("50.00");
            assertThat(s.accruingNotFinal().amount()).isEqualByComparingTo("7");
            assertThat(s.billable()).isFalse();
            assertThat(s.months()).hasSize(3);
            assertThat(s.reconciliationStatus().name()).isEqualTo("PENDING");

            // Per-account daily accrual and per-group months read the head run only (7, not 7 + 7).
            var daily = service.getDailyAccrual(account, java.time.LocalDate.parse("2026-11-01"), java.time.LocalDate.parse("2026-11-30"));
            assertThat(daily).singleElement().satisfies(d -> {
                assertThat(d.date()).isEqualTo(java.time.LocalDate.parse("2026-11-01"));
                assertThat(d.net().amount()).isEqualByComparingTo("7");
            });
            var months = service.getGroupMonths(account, group, YearMonth.of(2026, 6), YearMonth.of(2026, 11));
            assertThat(months).singleElement().satisfies(m -> {
                assertThat(m.status()).isEqualTo("OPEN");
                assertThat(m.net().amount()).isEqualByComparingTo("7");
            });
            assertThat(service.latestClosedAt(3L)).contains(Instant.parse("2026-10-05T08:00:00Z"));
            assertThat(service.latestClosedAt(99L)).isEmpty();
        }
    }
}
