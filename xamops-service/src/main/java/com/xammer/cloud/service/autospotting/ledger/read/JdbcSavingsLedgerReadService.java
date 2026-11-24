package com.xammer.cloud.service.autospotting.ledger.read;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.service.autospotting.ledger.money.Money;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * {@link SavingsLedgerReadService} over the ledger tables (design-ledger.md §10.2). Read only; every query
 * is parameterized and uses UTC month boundaries. Sums are exact {@code NUMERIC} sums read as
 * {@link BigDecimal}; nothing is converted to a binary float and nothing is rounded here: statement
 * totals are already at the currency's minor unit ({@code NUMERIC(19,2)}), and entry-based figures stay at
 * scale 10 for the DTO layer to round once for display.
 *
 * <p>"Current" entries are those of each window's head run (the run no other run supersedes), so a
 * superseded run never counts twice.
 */
@Service
public class JdbcSavingsLedgerReadService implements SavingsLedgerReadService {

    private static final Currency USD = Currency.getInstance("USD");
    private static final Money ZERO = Money.zero(USD);

    /** Current accrual entries, grouped; the {@code NOT EXISTS} keeps only each window's head run. */
    private static final String CURRENT_ENTRIES = " FROM savings_ledger_entry e JOIN savings_ledger_run r ON r.id = e.run_id"
            + " WHERE e.entry_type = 'ACCRUAL' AND NOT EXISTS (SELECT 1 FROM savings_ledger_run s"
            + " WHERE s.supersedes_run_id = r.id)";

    private final JdbcTemplate jdbc;

    public JdbcSavingsLedgerReadService(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    // ------------------------------------------------------------------ account summary

    @Override
    @Transactional(readOnly = true)
    public SavingsSummary getSavingsSummary(CloudAccount account, Instant periodStart, Instant periodEnd) {
        YearMonth from = monthOf(periodStart, "periodStart");
        YearMonth toExclusive = monthOf(periodEnd, "periodEnd");
        if (!toExclusive.isAfter(from)) {
            throw new IllegalArgumentException("periodEnd must be a later UTC month boundary than periodStart");
        }
        String provider = account.getProvider() == null ? "" : account.getProvider().toUpperCase(java.util.Locale.ROOT);
        Date fromDate = Date.valueOf(from.atDay(1));
        Date toDate = Date.valueOf(toExclusive.atDay(1));

        List<MonthRow> statements = jdbc.query("SELECT id, statement_month, status, invoiceable, total_billable,"
                        + " content_sha256, calc_version FROM savings_statement WHERE provider = ?"
                        + " AND cloud_account_id = ? AND statement_month >= ? AND statement_month < ?"
                        + " ORDER BY statement_month",
                (rs, i) -> new MonthRow(rs.getObject(1, java.util.UUID.class).toString(),
                        YearMonth.from(rs.getDate(2).toLocalDate()), rs.getString(3), rs.getBoolean(4),
                        rs.getBigDecimal(5), rs.getString(6), rs.getString(7)),
                provider, account.getId(), fromDate, toDate);

        List<EntryRow> entries = jdbc.query("SELECT e.statement_month, e.tier, e.reconciliation_status,"
                        + " count(*), sum(e.gross_savings), sum(e.net_savings)" + CURRENT_ENTRIES
                        + " AND e.provider = ? AND e.cloud_account_id = ? AND e.statement_month >= ?"
                        + " AND e.statement_month < ? GROUP BY 1, 2, 3",
                (rs, i) -> new EntryRow(YearMonth.from(rs.getDate(1).toLocalDate()), rs.getString(2),
                        rs.getString(3), rs.getLong(4), rs.getBigDecimal(5), rs.getBigDecimal(6)),
                provider, account.getId(), fromDate, toDate);

        Map<YearMonth, BigDecimal> closedRawNet = new TreeMap<>(); // GROUP_PERIOD lines of closed statements
        jdbc.query("SELECT st.statement_month, sum(l.raw_net) FROM savings_statement_line l JOIN savings_statement st"
                        + " ON st.id = l.statement_id WHERE l.line_kind = 'GROUP_PERIOD' AND st.provider = ?"
                        + " AND st.cloud_account_id = ? AND st.statement_month >= ? AND st.statement_month < ?"
                        + " AND st.status IN ('CLOSED', 'INVOICED') GROUP BY 1",
                rs -> {
                    closedRawNet.put(YearMonth.from(rs.getDate(1).toLocalDate()), rs.getBigDecimal(2));
                }, provider, account.getId(), fromDate, toDate);

        BigDecimal adjustments = jdbc.queryForObject("SELECT coalesce(sum(l.billable_amount), 0) FROM"
                        + " savings_statement_line l JOIN savings_statement st ON st.id = l.statement_id"
                        + " WHERE l.line_kind = 'ADJUSTMENT' AND st.provider = ? AND st.cloud_account_id = ?"
                        + " AND st.statement_month >= ? AND st.statement_month < ?"
                        + " AND st.status IN ('CLOSED', 'INVOICED')",
                BigDecimal.class, provider, account.getId(), fromDate, toDate);

        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal net = BigDecimal.ZERO;
        BigDecimal accruing = BigDecimal.ZERO;
        BigDecimal billable = BigDecimal.ZERO;
        BigDecimal shadow = BigDecimal.ZERO;
        Map<YearMonth, String> statusByMonth = new LinkedHashMap<>();
        boolean allBillable = !statements.isEmpty();
        for (MonthRow s : statements) {
            statusByMonth.put(s.month(), s.status());
            boolean closed = "CLOSED".equals(s.status()) || "INVOICED".equals(s.status());
            if (closed && s.invoiceable() && s.totalBillable() != null) {
                billable = billable.add(s.totalBillable());
            } else if (closed && !s.invoiceable() && s.totalBillable() != null) {
                shadow = shadow.add(s.totalBillable());
            }
            allBillable &= closed && s.invoiceable();
        }
        for (EntryRow e : entries) {
            gross = gross.add(e.gross());
            String status = statusByMonth.get(e.month());
            boolean closed = "CLOSED".equals(status) || "INVOICED".equals(status);
            if (!closed) {
                net = net.add(e.net());
                accruing = accruing.add(e.net());
            }
        }
        for (BigDecimal closedNet : closedRawNet.values()) {
            net = net.add(closedNet);
        }
        // A month with entries and no statement row yet is open too (the OPEN row is written with the first entry).
        boolean anyAdjustment = adjustments != null && adjustments.signum() != 0;
        ReconciliationStatus recon = reconciliation(entries, anyAdjustment);

        List<MonthSummary> months = new ArrayList<>();
        for (MonthRow s : statements) {
            Map<String, Long> tiers = new TreeMap<>();
            entries.stream().filter(e -> e.month().equals(s.month()))
                    .forEach(e -> tiers.merge(e.tier(), e.count(), Long::sum));
            months.add(new MonthSummary(s.id(), s.month(), s.status(), s.contentSha256(), s.calcVersion(), tiers,
                    s.invoiceable()));
        }
        return new SavingsSummary(account.getId(), provider, periodStart, periodEnd, usd(gross), usd(net),
                usd(adjustments == null ? BigDecimal.ZERO : adjustments), usd(billable), usd(shadow), usd(accruing),
                recon, allBillable, List.copyOf(months));
    }

    private static ReconciliationStatus reconciliation(List<EntryRow> entries, boolean anyAdjustment) {
        if (entries.isEmpty()) {
            return ReconciliationStatus.NONE;
        }
        boolean variance = entries.stream().anyMatch(e -> "VARIANCE_DETECTED".equals(e.reconciliation()));
        boolean pending = entries.stream().anyMatch(e -> "PENDING".equals(e.reconciliation()));
        if (variance) {
            return ReconciliationStatus.VARIANCE_DETECTED;
        }
        if (pending) {
            return ReconciliationStatus.PENDING;
        }
        return anyAdjustment ? ReconciliationStatus.ADJUSTED : ReconciliationStatus.RECONCILED;
    }

    // ------------------------------------------------------------------ client view

    @Override
    @Transactional(readOnly = true)
    public ClientSpotSavings getClientSpotSavings(Long clientId, YearMonth from, YearMonth to) {
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("to must not be before from");
        }
        Date fromDate = Date.valueOf(from.atDay(1));
        Date toDate = Date.valueOf(to.plusMonths(1).atDay(1));

        List<MonthRow2> statements = jdbc.query("SELECT cloud_account_id, provider, statement_month, status,"
                        + " invoiceable, total_billable FROM savings_statement WHERE client_id = ?"
                        + " AND statement_month >= ? AND statement_month < ? ORDER BY statement_month, cloud_account_id",
                (rs, i) -> new MonthRow2(rs.getLong(1), rs.getString(2), YearMonth.from(rs.getDate(3).toLocalDate()),
                        rs.getString(4), rs.getBoolean(5), rs.getBigDecimal(6)), clientId, fromDate, toDate);

        // Net of the current entries per account-month: the accruing figure of an open month.
        Map<String, BigDecimal> entryNet = new LinkedHashMap<>();
        jdbc.query("SELECT e.cloud_account_id, e.provider, e.statement_month, sum(e.net_savings)" + CURRENT_ENTRIES
                        + " AND e.client_id = ? AND e.statement_month >= ? AND e.statement_month < ? GROUP BY 1, 2, 3",
                rs -> {
                    entryNet.put(key(rs.getLong(1), YearMonth.from(rs.getDate(3).toLocalDate())), rs.getBigDecimal(4));
                }, clientId, fromDate, toDate);

        Map<String, BigDecimal[]> totals = new TreeMap<>(); // provider -> {realized, shadow, accruing}
        List<AccountMonth> months = new ArrayList<>();
        List<YearMonth> realizedMonths = new ArrayList<>();
        java.util.Set<String> accountMonthsWithStatement = new java.util.HashSet<>();
        for (MonthRow2 s : statements) {
            accountMonthsWithStatement.add(key(s.accountId(), s.month()));
            BigDecimal[] t = totals.computeIfAbsent(s.provider(), p -> new BigDecimal[] {BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO});
            boolean closed = "CLOSED".equals(s.status()) || "INVOICED".equals(s.status());
            BigDecimal realized = BigDecimal.ZERO;
            BigDecimal shadow = BigDecimal.ZERO;
            BigDecimal accruing = BigDecimal.ZERO;
            if (closed && s.invoiceable() && s.totalBillable() != null) {
                realized = s.totalBillable();
                if (!realizedMonths.contains(s.month())) {
                    realizedMonths.add(s.month());
                }
            } else if (closed && s.totalBillable() != null) {
                shadow = s.totalBillable();
            } else if (!closed) {
                accruing = entryNet.getOrDefault(key(s.accountId(), s.month()), BigDecimal.ZERO);
            }
            t[0] = t[0].add(realized);
            t[1] = t[1].add(shadow);
            t[2] = t[2].add(accruing);
            months.add(new AccountMonth(s.accountId(), s.provider(), s.month(), s.status(), s.invoiceable(),
                    usd(realized), usd(shadow), usd(accruing)));
        }
        List<ProviderSavings> providers = new ArrayList<>();
        totals.forEach((provider, t) -> providers.add(new ProviderSavings(provider, usd(t[0]), usd(t[1]), usd(t[2]))));

        List<DailyAccrual> daily = new ArrayList<>();
        jdbc.query("SELECT e.provider, (e.period_start AT TIME ZONE 'UTC')::date AS d, sum(e.net_savings)"
                        + CURRENT_ENTRIES + " AND e.client_id = ? AND e.statement_month >= ? AND e.statement_month < ?"
                        + " GROUP BY 1, 2 ORDER BY 2, 1",
                rs -> {
                    daily.add(new DailyAccrual(rs.getDate(2).toLocalDate(), rs.getString(1), usd(rs.getBigDecimal(3))));
                }, clientId, fromDate, toDate);
        return new ClientSpotSavings(List.copyOf(providers), List.copyOf(months), List.copyOf(realizedMonths),
                List.copyOf(daily));
    }

    @Override
    @Transactional(readOnly = true)
    public List<GroupMonth> getGroupMonths(CloudAccount account, java.util.UUID groupId, YearMonth from, YearMonth to) {
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("to must not be before from");
        }
        String provider = account.getProvider() == null ? "" : account.getProvider().toUpperCase(java.util.Locale.ROOT);
        return jdbc.query("SELECT e.statement_month, coalesce(st.status, 'OPEN'), coalesce(st.invoiceable, false),"
                        + " sum(e.net_savings)" + CURRENT_ENTRIES.replace(" WHERE ", " LEFT JOIN savings_statement st"
                        + " ON st.provider = e.provider AND st.cloud_account_id = e.cloud_account_id"
                        + " AND st.statement_month = e.statement_month WHERE ")
                        + " AND e.provider = ? AND e.cloud_account_id = ? AND e.group_id = ?"
                        + " AND e.statement_month >= ? AND e.statement_month < ? GROUP BY 1, 2, 3 ORDER BY 1",
                (rs, i) -> new GroupMonth(YearMonth.from(rs.getDate(1).toLocalDate()), rs.getString(2), rs.getBoolean(3),
                        usd(rs.getBigDecimal(4))),
                provider, account.getId(), groupId, Date.valueOf(from.atDay(1)), Date.valueOf(to.plusMonths(1).atDay(1)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<DailyAccrual> getDailyAccrual(CloudAccount account, LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("to must not be before from");
        }
        String provider = account.getProvider() == null ? "" : account.getProvider().toUpperCase(java.util.Locale.ROOT);
        return jdbc.query("SELECT (e.period_start AT TIME ZONE 'UTC')::date, sum(e.net_savings)" + CURRENT_ENTRIES
                        + " AND e.provider = ? AND e.cloud_account_id = ? AND e.period_start >= ? AND e.period_start < ?"
                        + " GROUP BY 1 ORDER BY 1",
                (rs, i) -> new DailyAccrual(rs.getDate(1).toLocalDate(), provider, usd(rs.getBigDecimal(2))),
                provider, account.getId(), Timestamp.from(from.atStartOfDay().toInstant(ZoneOffset.UTC)),
                Timestamp.from(to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Instant> latestClosedAt(Long clientId) {
        List<Timestamp> rows = jdbc.query("SELECT max(closed_at) FROM savings_statement WHERE client_id = ?",
                (rs, i) -> rs.getTimestamp(1), clientId);
        return rows.stream().filter(java.util.Objects::nonNull).map(Timestamp::toInstant).findFirst();
    }

    // ------------------------------------------------------------------ helpers

    private record MonthRow(String id, YearMonth month, String status, boolean invoiceable, BigDecimal totalBillable,
                            String contentSha256, String calcVersion) {
    }

    private record MonthRow2(long accountId, String provider, YearMonth month, String status, boolean invoiceable,
                             BigDecimal totalBillable) {
    }

    private record EntryRow(YearMonth month, String tier, String reconciliation, long count, BigDecimal gross,
                            BigDecimal net) {
    }

    private static String key(long accountId, YearMonth month) {
        return accountId + "|" + month;
    }

    private static Money usd(BigDecimal amount) {
        return amount == null ? ZERO : new Money(amount, USD);
    }

    /** The UTC month an instant opens; any other instant is not a month boundary. */
    private static YearMonth monthOf(Instant boundary, String name) {
        java.time.ZonedDateTime z = boundary.atZone(ZoneOffset.UTC);
        if (z.getDayOfMonth() != 1 || !z.toLocalTime().equals(java.time.LocalTime.MIDNIGHT)) {
            throw new IllegalArgumentException(name + " must be the first instant of a UTC month: " + boundary);
        }
        return YearMonth.from(z);
    }
}
