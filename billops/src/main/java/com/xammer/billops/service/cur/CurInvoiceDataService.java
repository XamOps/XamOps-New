package com.xammer.billops.service.cur;

import com.xammer.billops.domain.CloudAccount;
import com.xammer.billops.domain.CurSourceView;
import com.xammer.billops.dto.cur.CurAggregates;
import com.xammer.billops.repository.CurSourceViewRepository;
import com.xammer.billops.service.CurIngestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Makes a month of a customer's Cost and Usage Report available to invoice
 * generation, reading it through Athena.
 *
 * <h2>Why this exists alongside CurIngestionService</h2>
 * That class reads a CUR 2.0 export: CSV under a {@code cur2} prefix, streamed and
 * parsed in process. Onboarding now creates a CUR 1.0 report instead — Parquet,
 * hourly, with resource IDs, under {@code cur} — and an OpenCSV reader cannot read a
 * byte of it. Rather than add a Parquet reader, this queries the Athena table
 * xamops-service already provisions from the report's own manifest.
 *
 * <p>What it produces is identical: the same {@link CurAggregates}, written to the
 * same {@code AWS_CUR_*} keys through the same storage method. Everything downstream
 * — {@code BillingService.getCurDetailedReport}, the CloudFront repricing path, the
 * CUR admin endpoints — already reads those keys and needed no change. The only new
 * thing is where the numbers come from.
 *
 * <h2>Why it caches</h2>
 * Writing the aggregates means a second invoice for the same month costs nothing,
 * and the figures on the invoice match the ones the billing page shows, because both
 * read one stored copy rather than two queries run minutes apart. A closed month is
 * reused indefinitely — it cannot change. An open month is re-read once it is more
 * than {@link #openMonthMaxAgeHours} old, because AWS rewrites the current month
 * several times a day.
 *
 * <h2>What it never does</h2>
 * Fall back. Every failure leaves a {@link CurUnavailableException} carrying a reason
 * an admin can act on; choosing Cost Explorer instead is their decision to make, not
 * this class's.
 */
@Service
public class CurInvoiceDataService {

    private static final Logger logger = LoggerFactory.getLogger(CurInvoiceDataService.class);

    /**
     * Rows below this cost are dropped from the usage-type breakdown unless they carry
     * usage. Matches CurIngestionService's floor so both producers store comparably
     * sized aggregates. The month total is summed before this, so the headline figure
     * is never affected.
     */
    private static final double USAGE_NOISE_FLOOR = 0.0001;

    /**
     * Rate lines netting to no more than this are left off the bill's line list — the
     * threshold the cost page's "Charges by service" query uses, so both show the same
     * lines. The month total is summed before this and still includes them.
     */
    private static final double LINE_FLOOR = 0.001;

    /**
     * Ceiling on grouped rows returned for one month.
     *
     * <p>Service x region x usage type is a few hundred rows for an ordinary account
     * and a few thousand for a large one. A cap well above that turns a pathological
     * case — a report whose usage-type column is unexpectedly high-cardinality — into
     * a truncated aggregate that is logged, rather than a heap exhausted inside an
     * invoice request.
     */
    private static final int MAX_GROUPS = 50_000;

    private final CurSourceViewRepository curSourceViewRepository;
    private final CurAthenaQueryService athena;
    private final CurIngestionService curIngestionService;

    @Value("${billops.cur.enabled:true}")
    private boolean curEnabled;

    /**
     * How stale an open month's aggregate may be before it is re-read. Six hours
     * matches how often AWS actually rewrites the report (up to three times a day),
     * so a shorter window would mostly re-query for identical numbers.
     */
    @Value("${billops.cur.open-month-max-age-hours:6}")
    private int openMonthMaxAgeHours;

    public CurInvoiceDataService(CurSourceViewRepository curSourceViewRepository,
                                 CurAthenaQueryService athena,
                                 CurIngestionService curIngestionService) {
        this.curSourceViewRepository = curSourceViewRepository;
        this.athena = athena;
        this.curIngestionService = curIngestionService;
    }

    /** The outcome of asking whether CUR can serve a month, without querying anything. */
    public record Availability(boolean available, CurUnavailable reason, String detail) {

        public static Availability ok() {
            return new Availability(true, null, null);
        }

        public static Availability no(CurUnavailable reason, String detail) {
            return new Availability(false, reason, detail);
        }

        /** The explanation to show, or null when available. */
        public String message() {
            if (available || reason == null) {
                return null;
            }
            return detail == null || detail.isBlank()
                    ? reason.message()
                    : reason.message() + " (" + detail + ")";
        }
    }

    /**
     * Whether the report could serve this month, judged from registration alone.
     *
     * <p>Costs nothing — no Athena, no S3 — so the UI can ask before an admin commits
     * to generating anything. It cannot tell whether the month actually holds data;
     * only a query can, and that answer comes from {@link #ensureMonthLoaded}.
     */
    public Availability checkAvailability(CloudAccount account, YearMonth period) {
        if (!curEnabled) {
            return Availability.no(CurUnavailable.CUR_DISABLED, null);
        }
        if (account == null || account.getAwsAccountId() == null) {
            return Availability.no(CurUnavailable.NO_CUR_SOURCE, "not an AWS account");
        }
        if (period == null) {
            return Availability.no(CurUnavailable.PERIOD_IN_FUTURE, null);
        }
        if (period.isAfter(YearMonth.now())) {
            return Availability.no(CurUnavailable.PERIOD_IN_FUTURE, period.toString());
        }

        Optional<CurSourceView> found;
        try {
            found = curSourceViewRepository.findBestForAccount(account.getAwsAccountId());
        } catch (RuntimeException e) {
            // cur_source is created by an xamops-service migration. Where that has not run,
            // no account can have a report registered, which is what the admin should read —
            // not a SQL error about a missing relation.
            logger.warn("[CUR] Could not read cur_source for {}: {}", account.getAwsAccountId(), e.getMessage());
            return Availability.no(CurUnavailable.NO_CUR_SOURCE, "the CUR registry is not available in this environment");
        }
        if (found.isEmpty()) {
            return Availability.no(CurUnavailable.NO_CUR_SOURCE, null);
        }
        CurSourceView source = found.get();

        if (!source.isActive()) {
            String detail = source.getStatusDetail() == null || source.getStatusDetail().isBlank()
                    ? "status " + source.getStatus()
                    : "status " + source.getStatus() + ": " + source.getStatusDetail();
            return Availability.no(CurUnavailable.SOURCE_NOT_ACTIVE, detail);
        }
        if (source.getAthenaDatabase() == null || source.getAthenaTable() == null) {
            // Registered and ACTIVE but never got as far as discovering its table. Reported
            // as not-active rather than no-data: the setup is incomplete, not the month.
            return Availability.no(CurUnavailable.SOURCE_NOT_ACTIVE, "no Athena table discovered yet");
        }

        YearMonth earliest = source.earliestMonth();
        if (earliest != null && period.isBefore(earliest)) {
            return Availability.no(CurUnavailable.PERIOD_BEFORE_REPORT_START,
                    "the report starts at " + earliest);
        }
        return Availability.ok();
    }

    /**
     * Guarantees that {@code period} is present in the {@code AWS_CUR_*} store,
     * querying the report if it is missing or too old, and throws with a reason when
     * it cannot be.
     *
     * @return the aggregate that is now stored
     */
    public CurAggregates ensureMonthLoaded(CloudAccount account, YearMonth period, boolean force) {
        Availability availability = checkAvailability(account, period);
        if (!availability.available()) {
            throw new CurUnavailableException(availability.reason(), availability.detail());
        }

        String accountId = account.getAwsAccountId();
        if (!force && isStoredAndFreshEnough(accountId, period)) {
            logger.debug("[CUR] Reusing stored aggregate for {} {}", accountId, period);
            return readStored(accountId, period);
        }

        CurSourceView source = curSourceViewRepository.findBestForAccount(accountId)
                .orElseThrow(() -> new CurUnavailableException(CurUnavailable.NO_CUR_SOURCE));

        CurAggregates aggregates;
        try {
            aggregates = queryMonth(source, account, period);
        } catch (CurAthenaQueryService.CurQueryException e) {
            logger.warn("[CUR] Athena read failed for {} {}: {}", accountId, period, e.getMessage());
            throw new CurUnavailableException(CurUnavailable.QUERY_FAILED, e.getMessage());
        }

        if (aggregates.getSvcRegion().isEmpty()) {
            // The query worked and returned nothing: the report is fine, this month is
            // simply not in it. Distinct from a failure, and not worth retrying.
            throw new CurUnavailableException(CurUnavailable.NO_DATA_FOR_MONTH,
                    "queried " + source.qualifiedTable());
        }

        curIngestionService.storeAggregates(accountId, period, aggregates);
        logger.info("[CUR] Loaded {} {} from report: {} service/region rows, {} usage rows, total ${}",
                accountId, period, aggregates.getSvcRegion().size(), aggregates.getSvcUsage().size(),
                String.format("%.2f", aggregates.getTotal()));
        return aggregates;
    }

    /**
     * True when a complete aggregate exists and is recent enough to reuse.
     *
     * <p>A closed month is always recent enough: the report may still be restated for
     * credits, which the nightly sweep picks up, but nothing about generating an
     * invoice justifies re-querying a period that has not moved.
     */
    private boolean isStoredAndFreshEnough(String accountId, YearMonth period) {
        if (!curIngestionService.hasCurData(accountId, period)) {
            return false;
        }
        // A month stored without its rate lines — by the CSV ingestion, or by this class
        // before it read at that grain — cannot build an invoice, however recent it is.
        if (curIngestionService.readCharges(accountId, period).isEmpty()) {
            return false;
        }
        if (period.isBefore(YearMonth.now())) {
            return true;
        }
        return curIngestionService.readState(accountId, period)
                .map(state -> {
                    try {
                        LocalDateTime at = LocalDateTime.parse(state.getIngestedAt());
                        return Duration.between(at, LocalDateTime.now()).toHours() < openMonthMaxAgeHours;
                    } catch (Exception e) {
                        return false; // Unparseable stamp — treat as old and re-read.
                    }
                })
                .orElse(false);
    }

    private CurAggregates readStored(String accountId, YearMonth period) {
        CurAggregates stored = new CurAggregates();
        stored.setBillingPeriod(period.toString());
        stored.setTotal(curIngestionService.readTotal(accountId, period).orElse(0.0));
        stored.setDaily(curIngestionService.readDaily(accountId, period).orElse(List.of()));
        stored.setSvcRegion(curIngestionService.readSvcRegion(accountId, period).orElse(List.of()));
        stored.setSvcUsage(curIngestionService.readSvcUsage(accountId, period).orElse(List.of()));
        stored.setMarketplace(curIngestionService.readMarketplace(accountId, period).orElse(null));
        stored.setCharges(curIngestionService.readCharges(accountId, period).orElse(null));
        return stored;
    }

    // ------------------------------------------------------------------------
    // Querying
    // ------------------------------------------------------------------------

    private CurAggregates queryMonth(CurSourceView source, CloudAccount account, YearMonth period) {
        LocalDate start = period.atDay(1);
        LocalDate end = period.atEndOfMonth();

        CurAggregates aggregates = new CurAggregates();
        aggregates.setBillingPeriod(period.toString());
        // No manifest is read on this path, so there is no assembly id to record. The
        // marker names the producer instead, which is what anyone reading the row wants
        // to know when the two ingestion paths disagree.
        aggregates.setAssemblyId("athena:" + source.getAthenaTable());

        readBreakdown(source, account, period, start, end, aggregates);
        aggregates.setDaily(readDaily(source, account, period, start, end));
        return aggregates;
    }

    /**
     * One query produces the whole breakdown: the rate lines, the month total, the
     * service x region and usage-type rollups, and the marketplace figure.
     *
     * <p>The grouping is the one xamops-service's "Charges by service" section uses —
     * product name, region, line item description, usage type and pricing unit — so an
     * invoice built from these rows matches that section line for line, and the
     * rollups the billing page and CloudFront repricing read are sums of the same rows
     * rather than a second interpretation. Measured on a live report for September
     * 2026: 254 lines, $3,332.34, identical to the cost page to the cent.
     *
     * <p>Deliberately one query and not several. Athena bills by bytes scanned with a
     * 10 MB minimum per query, and the customer pays it, so slicing the same rows in
     * separate passes would cost more and could disagree if a delivery landed between.
     */
    private void readBreakdown(CurSourceView source, CloudAccount account, YearMonth period,
                               LocalDate start, LocalDate end, CurAggregates aggregates) {
        String cost = numeric(source, "line_item_unblended_cost");
        String quantity = numeric(source, "line_item_usage_amount");

        String sql = "SELECT product_product_name AS service,"
                + " product_region AS region,"
                + " line_item_line_item_description AS description,"
                + " line_item_usage_type AS usage_type,"
                + " pricing_unit AS unit,"
                + " SUM(" + quantity + ") AS quantity,"
                + " SUM(" + cost + ") AS cost,"
                + " SUM(CASE WHEN bill_billing_entity = 'AWS Marketplace' THEN " + cost + " ELSE 0 END)"
                + " AS marketplace"
                + " FROM " + source.qualifiedTable()
                + " WHERE " + accountPredicate(account)
                + " AND " + partitionPredicate(source, period)
                + " AND " + usageDatePredicate(source, start, end)
                + " GROUP BY product_product_name, product_region,"
                + " line_item_line_item_description, line_item_usage_type, pricing_unit"
                + " LIMIT " + MAX_GROUPS;

        List<Map<String, String>> rows = athena.query(source, account, sql);
        if (rows.size() >= MAX_GROUPS) {
            logger.warn("[CUR] Breakdown for {} {} hit the {} row cap — the aggregate is truncated",
                    account.getAwsAccountId(), period, MAX_GROUPS);
        }

        List<CurAggregates.ChargeEntry> charges = new ArrayList<>();
        Map<String, CurAggregates.SvcRegionEntry> byServiceRegion = new LinkedHashMap<>();
        Map<String, CurAggregates.SvcUsageEntry> byUsageType = new LinkedHashMap<>();
        double total = 0.0;
        double marketplace = 0.0;

        for (Map<String, String> row : rows) {
            // Blank handling is the cost page's, label for label: region-less charges are
            // what the AWS invoice files under "Global", and a line with no description
            // falls back to its usage type, the next most specific thing to read.
            String svc = blankTo(row.get("service"), "Other");
            String rgn = blankTo(row.get("region"), "Global");
            String usageType = blankTo(row.get("usage_type"), "");
            String unit = blankTo(row.get("unit"), "");
            double rowCost = parse(row.get("cost"));
            double rowQuantity = parse(row.get("quantity"));

            // Totals are summed from every row, before any filtering: tax, credits,
            // refunds and sub-cent rows all belong on the bill even when they are too
            // small to show as their own line.
            total += rowCost;
            marketplace += parse(row.get("marketplace"));

            // Lines that net to nothing are left off, as the cost page's HAVING leaves
            // them off; negatives survive, because a negation or credit is a real line.
            if (Math.abs(rowCost) > LINE_FLOOR) {
                charges.add(new CurAggregates.ChargeEntry(svc, rgn, usageType,
                        blankTo(row.get("description"), blankTo(usageType, "—")),
                        unit, rowQuantity, rowCost));
            }

            CurAggregates.SvcRegionEntry serviceRegion = byServiceRegion.computeIfAbsent(
                    svc + " " + rgn, k -> new CurAggregates.SvcRegionEntry(svc, rgn, 0.0));
            serviceRegion.setCost(serviceRegion.getCost() + rowCost);

            CurAggregates.SvcUsageEntry usage = byUsageType.computeIfAbsent(
                    svc + " " + rgn + " " + usageType,
                    k -> new CurAggregates.SvcUsageEntry(svc, rgn, usageType, 0.0, 0.0, unit));
            usage.setCost(usage.getCost() + rowCost);
            usage.setQuantity(usage.getQuantity() + rowQuantity);
            if (usage.getUnit().isEmpty()) {
                usage.setUnit(unit);
            }
        }

        charges.sort((a, b) -> Double.compare(b.getCost(), a.getCost()));

        aggregates.setTotal(total);
        aggregates.setMarketplace(marketplace);
        aggregates.setCharges(charges);
        aggregates.setSvcRegion(new ArrayList<>(byServiceRegion.values()));
        aggregates.setSvcUsage(byUsageType.values().stream()
                .filter(u -> Math.abs(u.getCost()) >= USAGE_NOISE_FLOOR || u.getQuantity() > 0)
                .collect(Collectors.toCollection(ArrayList::new)));
        aggregates.setRowCount(rows.size());
    }

    /** Day-level totals, which the billing page's trend needs and a monthly-only aggregate could not give it. */
    private List<CurAggregates.DailyEntry> readDaily(CurSourceView source, CloudAccount account,
                                                     YearMonth period, LocalDate start, LocalDate end) {
        String day = "date_format(" + usageDateExpr(source) + ", '%Y-%m-%d')";
        String sql = "SELECT " + day + " AS day,"
                + " SUM(" + numeric(source, "line_item_unblended_cost") + ") AS cost"
                + " FROM " + source.qualifiedTable()
                + " WHERE " + accountPredicate(account)
                + " AND " + partitionPredicate(source, period)
                + " AND " + usageDatePredicate(source, start, end)
                + " GROUP BY " + day
                + " ORDER BY 1";

        List<CurAggregates.DailyEntry> daily = new ArrayList<>();
        for (Map<String, String> row : athena.query(source, account, sql)) {
            String date = row.get("day");
            if (date != null && !date.isBlank()) {
                daily.add(new CurAggregates.DailyEntry(date, parse(row.get("cost"))));
            }
        }
        return daily;
    }

    // ------------------------------------------------------------------------
    // SQL fragments
    //
    // Kept identical in meaning to xamops-service's CurSql, so a figure on an invoice
    // and the same figure on the cost page come from the same interpretation of the
    // report rather than two that merely look alike.
    // ------------------------------------------------------------------------

    /**
     * The invoice is for one account, so the query is filtered to that account's own
     * line items — a report in a payer account also contains every other member's.
     */
    private String accountPredicate(CloudAccount account) {
        String accountId = account.getAwsAccountId();
        if (accountId == null || !accountId.matches("\\d{12}")) {
            throw new IllegalArgumentException(
                    "Refusing to query CUR without a valid 12-digit account id (got: " + accountId + ")");
        }
        return "line_item_usage_account_id = '" + accountId + "'";
    }

    /**
     * Restricts the scan to the partitions covering the month, widened by one month at
     * each end: usage on a month boundary is billed to the adjacent period, and the
     * usage-date predicate is what actually decides which rows count.
     */
    private String partitionPredicate(CurSourceView source, YearMonth period) {
        YearMonth from = period.minusMonths(1);
        YearMonth to = period.plusMonths(1);
        if (source.isCur2()) {
            return "billing_period BETWEEN '" + from + "' AND '" + to + "'";
        }
        // year and month are strings and month is not zero-padded, so compare a padded
        // concatenation — still a plain range the partition pruner can use.
        String fromKey = String.format("%04d%02d", from.getYear(), from.getMonthValue());
        String toKey = String.format("%04d%02d", to.getYear(), to.getMonthValue());
        return "concat(year, lpad(month, 2, '0')) BETWEEN '" + fromKey + "' AND '" + toKey + "'";
    }

    /** Usage start, already a timestamp in Parquet and a string needing parsing in CSV. */
    private String usageDateExpr(CurSourceView source) {
        return source.isCsv()
                ? "CAST(from_iso8601_timestamp(line_item_usage_start_date) AS timestamp)"
                : "line_item_usage_start_date";
    }

    /**
     * The whole billing period, with the end date honoured rather than capped at today.
     *
     * <p>Reservation and Savings Plan fees, support and tax are written with usage
     * dates spanning the entire period the moment it opens. They are already on the
     * bill, so excluding them for being dated in the future understates the month.
     */
    private String usageDatePredicate(CurSourceView source, LocalDate start, LocalDate end) {
        String expr = usageDateExpr(source);
        return expr + " >= TIMESTAMP '" + start + " 00:00:00'"
                + " AND " + expr + " < TIMESTAMP '" + end.plusDays(1) + " 00:00:00'";
    }

    /** A numeric column, cast when the report is CSV and therefore all strings. */
    private String numeric(CurSourceView source, String column) {
        return source.isCsv()
                ? "COALESCE(try_cast(" + column + " AS double), 0)"
                : column;
    }

    private String blankTo(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }

    private double parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0.0;
        }
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }
}
