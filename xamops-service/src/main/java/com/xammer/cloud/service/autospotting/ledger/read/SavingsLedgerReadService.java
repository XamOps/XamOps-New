package com.xammer.cloud.service.autospotting.ledger.read;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.service.autospotting.ledger.money.Money;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The one reader of ledger savings for display (design-ledger.md §10.2, FR-LED-27, AC-LED-11): the Client
 * Savings tab, the AutoSpotting admin dashboard, the per-cloud history charts and the customer statement
 * pages all read here, so no screen can add a figure the ledger does not stand behind. BillOps fee
 * invoicing does not read here: it reads the fee-basis view (§10.1).
 *
 * <p><strong>Shadow safety.</strong> A figure is {@code billableSavings} only when its statement is CLOSED or
 * INVOICED <em>and</em> invoiceable. A closed statement that is not invoiceable (the shadow months, billing
 * off) goes to {@code shadowNotInvoiced}, which callers must show only under the label "Not invoiced". An
 * open month goes to {@code accruingNotFinal} only. Legacy rows are never read here. Money is
 * {@link Money} in USD (v1 is USD only); the DTO layer serializes it as a string with the currency code.
 */
public interface SavingsLedgerReadService {

    /**
     * The savings of one account over whole UTC months. Takes the guard-resolved {@link CloudAccount}, never
     * a raw id (core §11). {@code periodStart} must be the first instant of a UTC month and {@code periodEnd}
     * the first instant of a later UTC month (exclusive); anything else throws {@link IllegalArgumentException}.
     */
    SavingsSummary getSavingsSummary(CloudAccount account, Instant periodStart, Instant periodEnd);

    /** A client's Spot savings per provider across its accounts, for the months {@code from} to {@code to} inclusive. */
    ClientSpotSavings getClientSpotSavings(Long clientId, YearMonth from, YearMonth to);

    /** The newest {@code closed_at} of any of the client's statements: the cache-key part that makes a close visible at once. */
    Optional<Instant> latestClosedAt(Long clientId);

    /**
     * One group's measured net per statement month (current entries only), with the month's statement status,
     * for the detail panel's Savings tab. An open month (or one with no statement row yet) is "Accruing (not
     * final)"; a closed one that is not invoiceable is "Not invoiced". The account is the guard-resolved one.
     */
    List<GroupMonth> getGroupMonths(CloudAccount account, java.util.UUID groupId, YearMonth from, YearMonth to);

    /**
     * The account's daily accrual over UTC days {@code from} to {@code to} inclusive: the net of the current
     * entries of each day, before statement rounding. Days with no entry are absent. For history charts.
     */
    List<DailyAccrual> getDailyAccrual(CloudAccount account, LocalDate from, LocalDate to);

    /** A group's month: {@code status} is OPEN, CLOSED or INVOICED (OPEN when no statement row exists yet). */
    record GroupMonth(YearMonth month, String status, boolean invoiceable, Money net) {
    }

    /** Worst of VARIANCE_DETECTED, PENDING, RECONCILED; ADJUSTED when reconciled but adjustments reference it. */
    enum ReconciliationStatus { NONE, RECONCILED, ADJUSTED, PENDING, VARIANCE_DETECTED }

    /** One statement month of a summary. */
    record MonthSummary(String statementId, YearMonth month, String status, String contentSha256, String calcVersion,
                        Map<String, Long> tierCounts, boolean invoiceable) {
    }

    record SavingsSummary(Long cloudAccountId, String provider, Instant periodStart, Instant periodEnd,
                          Money grossSavings, Money netSavings, Money adjustments, Money billableSavings,
                          Money shadowNotInvoiced, Money accruingNotFinal, ReconciliationStatus reconciliationStatus,
                          boolean billable, List<MonthSummary> months) {
    }

    /** One account-month of a client's savings. */
    record AccountMonth(Long cloudAccountId, String provider, YearMonth month, String status, boolean invoiceable,
                        Money realized, Money shadowNotInvoiced, Money accruing) {
    }

    /** One provider's totals over the range: realized (invoiceable closed statements only), not invoiced, accruing. */
    record ProviderSavings(String provider, Money realized, Money shadowNotInvoiced, Money accruing) {
    }

    /** The daily accrual of a provider: the net of the current entries of the day, before statement rounding. */
    record DailyAccrual(LocalDate date, String provider, Money net) {
    }

    record ClientSpotSavings(List<ProviderSavings> providers, List<AccountMonth> months,
                             List<YearMonth> realizedMonths, List<DailyAccrual> daily) {
        public ProviderSavings provider(String provider) {
            return providers.stream().filter(p -> p.provider().equals(provider)).findFirst()
                    .orElseGet(() -> new ProviderSavings(provider, Money.zero(java.util.Currency.getInstance("USD")),
                            Money.zero(java.util.Currency.getInstance("USD")),
                            Money.zero(java.util.Currency.getInstance("USD"))));
        }
    }
}
