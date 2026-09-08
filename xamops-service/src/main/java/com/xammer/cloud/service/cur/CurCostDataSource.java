package com.xammer.cloud.service.cur;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.CurSource;
import com.xammer.cloud.dto.CostDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cost queries answered from a Cost and Usage Report instead of the Cost
 * Explorer API.
 *
 * <p>Covers the four widgets that share one query shape — month-to-date spend,
 * cost breakdown, cost trend and top services — which between them are the whole
 * of the Dashboard cost card, the Cost page and the FinOps billing summary.
 *
 * <h2>Matching Cost Explorer's numbers</h2>
 * These queries sum <em>every</em> line item type, not just {@code Usage}. That
 * looks wrong at a glance and is the single most important detail here:
 * {@code GetCostAndUsage} with no filter — which is how every existing call in
 * CostService and FinOpsService is built — includes credits, refunds, tax and
 * fees. Filtering to {@code line_item_line_item_type = 'Usage'} would produce a
 * CUR number permanently below the CE number by the credit and tax amount on
 * every account that has any, and the two paths have to agree because a single
 * account can be served by either depending on the range requested.
 *
 * <h2>Tenant isolation</h2>
 * A XamOps MPA's CUR contains every customer under that payer in one table, so
 * the {@code usage_account_id} predicate is the only thing separating one
 * tenant's costs from another's. It is built here, from the account the caller
 * passed, and there is no code path that omits it — callers cannot supply their
 * own WHERE clause.
 */
@Service
public class CurCostDataSource {

    private static final Logger logger = LoggerFactory.getLogger(CurCostDataSource.class);

    /**
     * Dimensions a caller may group by, mapped to their CUR column.
     *
     * <p>A whitelist rather than escaping: {@code groupBy} arrives as a query
     * parameter from the browser and is interpolated into SQL. Anything not on
     * this map is rejected rather than passed through.
     *
     * <p>SERVICE maps to {@code product_product_name}, not
     * {@code line_item_product_code}. The product code is the compact identifier
     * ("AmazonEC2"), while Cost Explorer's SERVICE dimension returns a display name
     * ("Amazon Elastic Compute Cloud - Compute"). Both sources write to the same
     * shared breakdown cache that the Dashboard, Cost and FinOps pages read, so a
     * mismatch would make service labels change depending on which source happened
     * to fill it. {@code product_product_name} is the closest CUR equivalent —
     * still not character-identical to CE for every service (CE splits out buckets
     * like "EC2 - Other" that CUR leaves under the product name), which is a known
     * and accepted difference between the two paths.
     */
    private static final Map<String, String> GROUP_BY_COLUMNS = Map.of(
            "SERVICE", "product_product_name",
            "REGION", "product_region",
            "USAGE_TYPE", "line_item_usage_type",
            "INSTANCE_TYPE", "product_instance_type",
            "LINKED_ACCOUNT", "line_item_usage_account_id",
            "RECORD_TYPE", "line_item_line_item_type");

    /** Cost column. Unblended matches what every existing Cost Explorer call requests. */
    private static final String COST_COLUMN = "line_item_unblended_cost";

    /**
     * The cost column, cast if the report needs it.
     *
     * <p>A CSV CUR is read through OpenCSVSerde, which types every column as a string,
     * so summing without a cast fails. Parquet needs no cast. Every numeric reference
     * goes through here so one set of queries serves both formats.
     */
    private static String cost(CurSource source) {
        return CurSql.numericExpr(source, COST_COLUMN);
    }

    private final CurAthenaQueryService athena;

    public CurCostDataSource(CurAthenaQueryService athena) {
        this.athena = athena;
    }

    /**
     * Cost grouped by a dimension over a date range — the Cost Breakdown chart,
     * the Top Services list, and (summed) the month-to-date figure.
     */
    public List<CostDto> getCostBreakdown(CurSource source, CloudAccount account, String groupBy,
                                          String tagKey, LocalDate start, LocalDate end) {

        String groupColumn = resolveGroupColumn(groupBy, tagKey);

        String sql = "SELECT " + groupColumn + " AS grp,"
                + " SUM(" + cost(source) + ") AS cost"
                + " FROM " + source.qualifiedTable()
                + " WHERE " + accountPredicate(account)
                + " AND " + partitionPredicate(source, start, end)
                + " AND " + usageDatePredicate(source, start, end)
                + " GROUP BY " + groupColumn
                + " HAVING SUM(" + cost(source) + ") > 0.01"
                + " ORDER BY cost DESC";

        List<Map<String, String>> rows = athena.query(source, account, sql);

        List<CostDto> costs = new ArrayList<>(rows.size());
        for (Map<String, String> row : rows) {
            String name = row.get("grp");
            if (name == null || name.isBlank()) {
                // Matches the CE path, which relabels a null group key rather than
                // dropping it — untagged spend is real spend and has to show up.
                name = "TAG".equalsIgnoreCase(groupBy) ? "Untagged" : "Other";
            }
            costs.add(new CostDto(name, parseCost(row.get("cost"))));
        }

        logger.info("✅ [CUR] Breakdown by {} — {} rows for account {} ({} to {})",
                groupBy, costs.size(), account.getAwsAccountId(), start, end);
        return costs;
    }

    /** Total spend over a range — month-to-date when the range is the current month. */
    public double getTotalCost(CurSource source, CloudAccount account, LocalDate start, LocalDate end) {
        String sql = "SELECT SUM(" + cost(source) + ") AS cost"
                + " FROM " + source.qualifiedTable()
                + " WHERE " + accountPredicate(account)
                + " AND " + partitionPredicate(source, start, end)
                + " AND " + usageDatePredicate(source, start, end);

        List<Map<String, String>> rows = athena.query(source, account, sql);
        double total = rows.isEmpty() ? 0.0 : parseCost(rows.get(0).get("cost"));

        logger.info("✅ [CUR] Total cost {} for account {} ({} to {})",
                total, account.getAwsAccountId(), start, end);
        return total;
    }

    /**
     * Monthly totals for the Cost Trend chart, oldest first.
     *
     * <p>Returns a map rather than a list so a month with no rows is
     * distinguishable from a month that summed to zero — the caller fills gaps
     * explicitly instead of a missing month silently shortening the series.
     */
    public Map<YearMonth, Double> getMonthlyTrend(CurSource source, CloudAccount account,
                                                  YearMonth from, YearMonth to) {

        LocalDate start = from.atDay(1);
        LocalDate end = to.atEndOfMonth();

        // Bucket on usage start date, not the billing-period partition: usage on a month
        // boundary can be billed to the adjacent period, and grouping by partition would
        // attribute it to the wrong bar. Same reasoning as the GCP ingest, which buckets
        // by usage date rather than invoice_month.
        String sql = "SELECT date_format(" + usageDateExpr(source) + ", '%Y-%m') AS ym,"
                + " SUM(" + cost(source) + ") AS cost"
                + " FROM " + source.qualifiedTable()
                + " WHERE " + accountPredicate(account)
                + " AND " + partitionPredicate(source, start, end)
                + " AND " + usageDatePredicate(source, start, end)
                + " GROUP BY date_format(" + usageDateExpr(source) + ", '%Y-%m')"
                + " ORDER BY ym";

        List<Map<String, String>> rows = athena.query(source, account, sql);

        Map<YearMonth, Double> trend = new LinkedHashMap<>();
        for (Map<String, String> row : rows) {
            String ym = row.get("ym");
            if (ym == null || ym.isBlank()) {
                continue;
            }
            trend.put(YearMonth.parse(ym), parseCost(row.get("cost")));
        }

        logger.info("✅ [CUR] Monthly trend — {} months for account {} ({} to {})",
                trend.size(), account.getAwsAccountId(), from, to);
        return trend;
    }

    /**
     * Whether this report actually contains rows for the account.
     *
     * <p>The guard against the worst failure mode in a multi-MPA setup. XamOps can
     * operate several payer accounts, each with its own CUR, and an account assigned
     * to the wrong one produces a query that succeeds and matches nothing — which
     * renders as $0 spend, indistinguishable from an account that genuinely spent
     * nothing. The GCP reseller path shipped exactly this bug
     * (V20260818_2__gcp_billing_source.sql); one cheap query at assignment time is
     * what stops it recurring here.
     *
     * <p>Looks back two months rather than one so an account onboarded mid-month, or
     * checked on the 1st, is not judged on a partition that has barely any rows yet.
     */
    public boolean containsAccount(CurSource source, CloudAccount account) {
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusMonths(2).withDayOfMonth(1);

        String sql = "SELECT line_item_usage_account_id"
                + " FROM " + source.qualifiedTable()
                + " WHERE " + accountPredicate(account)
                + " AND " + CurSql.partitionPredicate(source, start, end)
                + " LIMIT 1";

        boolean present = !athena.query(source, account, sql).isEmpty();
        logger.info("[CUR] Account {} {} in source {}", account.getAwsAccountId(),
                present ? "found" : "NOT found", source.getId());
        return present;
    }

    /** Daily totals for the historical cost chart. Requires an HOURLY or DAILY report. */
    public Map<LocalDate, Double> getDailyTrend(CurSource source, CloudAccount account,
                                                LocalDate start, LocalDate end) {
        return getDailyTrend(source, account, start, end, null, null);
    }

    /**
     * Daily totals, optionally narrowed to one service and/or region.
     *
     * <p>The service filter matches {@code product_product_name} because that is what
     * {@code getCostBreakdown("SERVICE")} returns — a caller drilling into a service
     * passes back the name it was given, so the two have to agree on the column.
     */
    public Map<LocalDate, Double> getDailyTrend(CurSource source, CloudAccount account,
                                                LocalDate start, LocalDate end,
                                                String serviceName, String regionName) {
        if (!source.supportsDailyGranularity()) {
            throw new CurAthenaQueryService.CurQueryException(
                    "CUR source " + source.getId() + " is " + source.getTimeUnit()
                            + " granularity and cannot serve a daily series");
        }

        String sql = "SELECT date_format(" + usageDateExpr(source) + ", '%Y-%m-%d') AS day,"
                + " SUM(" + cost(source) + ") AS cost"
                + " FROM " + source.qualifiedTable()
                + " WHERE " + accountPredicate(account)
                + " AND " + partitionPredicate(source, start, end)
                + " AND " + usageDatePredicate(source, start, end)
                + dimensionFilter("product_product_name", serviceName)
                + dimensionFilter("product_region", regionName)
                + " GROUP BY date_format(" + usageDateExpr(source) + ", '%Y-%m-%d')"
                + " ORDER BY day";

        List<Map<String, String>> rows = athena.query(source, account, sql);

        Map<LocalDate, Double> trend = new LinkedHashMap<>();
        for (Map<String, String> row : rows) {
            String day = row.get("day");
            if (day == null || day.isBlank()) {
                continue;
            }
            trend.put(LocalDate.parse(day), parseCost(row.get("cost")));
        }
        return trend;
    }

    /**
     * Cost grouped by service and region together, for the detailed breakdown.
     *
     * <p>Returned as {@code service|region -> cost} entries in descending cost order;
     * the caller maps them into its own DTO.
     */
    public List<ServiceRegionCost> getCostByServiceAndRegion(CurSource source, CloudAccount account,
                                                             LocalDate start, LocalDate end) {
        String sql = "SELECT product_product_name AS svc, product_region AS rgn,"
                + " SUM(" + cost(source) + ") AS cost"
                + " FROM " + source.qualifiedTable()
                + " WHERE " + accountPredicate(account)
                + " AND " + partitionPredicate(source, start, end)
                + " AND " + usageDatePredicate(source, start, end)
                + " GROUP BY product_product_name, product_region"
                + " HAVING SUM(" + cost(source) + ") > 0.01"
                + " ORDER BY cost DESC";

        List<Map<String, String>> rows = athena.query(source, account, sql);

        List<ServiceRegionCost> costs = new ArrayList<>(rows.size());
        for (Map<String, String> row : rows) {
            costs.add(new ServiceRegionCost(
                    blankTo(row.get("svc"), "Other"),
                    // CE reports region-less charges (support, tax, some Marketplace) as
                    // "NoRegion"; CUR leaves the column empty. Normalise so the two paths
                    // produce the same label.
                    blankTo(row.get("rgn"), "NoRegion"),
                    parseCost(row.get("cost"))));
        }
        return costs;
    }

    /**
     * Credits applied over a period, returned positive.
     *
     * <p>Credits are stored as negative amounts — they reduce the bill — so the sign is
     * flipped to match what the Cost Explorer path returns, which applies
     * {@code Math::abs} for the same reason.
     */
    public double getCreditsApplied(CurSource source, CloudAccount account,
                                    LocalDate start, LocalDate end) {
        String sql = "SELECT SUM(" + cost(source) + ") AS cost"
                + " FROM " + source.qualifiedTable()
                + " WHERE " + accountPredicate(account)
                + " AND " + partitionPredicate(source, start, end)
                + " AND " + usageDatePredicate(source, start, end)
                + " AND line_item_line_item_type = 'Credit'";

        List<Map<String, String>> rows = athena.query(source, account, sql);
        return rows.isEmpty() ? 0.0 : Math.abs(parseCost(rows.get(0).get("cost")));
    }

    /** One service/region pair and its cost. */
    public record ServiceRegionCost(String service, String region, double cost) {
    }

    // ── SQL fragments ───────────────────────────────────────────────────────────

    /**
     * The tenant boundary. Every query in this class includes it, and no method
     * accepts a caller-supplied predicate that could replace it.
     */
    private String accountPredicate(CloudAccount account) {
        String accountId = account.getAwsAccountId();
        if (accountId == null || !accountId.matches("\\d{12}")) {
            throw new IllegalArgumentException(
                    "Refusing to query CUR without a valid 12-digit account id (got: " + accountId + ")");
        }
        return "line_item_usage_account_id = '" + accountId + "'";
    }

    private String partitionPredicate(CurSource source, LocalDate start, LocalDate end) {
        return CurSql.partitionPredicate(source, start, end);
    }

    private String usageDateExpr(CurSource source) {
        return CurSql.usageDateExpr(source);
    }

    private String usageDatePredicate(CurSource source, LocalDate start, LocalDate end) {
        return CurSql.usageDatePredicate(source, start, end);
    }

    /**
     * Resolves a caller's groupBy to a column, rejecting anything unrecognised.
     *
     * <p>Tag grouping reads a {@code resource_tags_user_*} column, whose name is
     * derived from the tag key. The key is validated character by character rather
     * than escaped, because it lands in an identifier position where quoting does
     * not make arbitrary input safe.
     */
    private String resolveGroupColumn(String groupBy, String tagKey) {
        if ("TAG".equalsIgnoreCase(groupBy)) {
            if (tagKey == null || !tagKey.matches("[A-Za-z0-9_:\\-]{1,128}")) {
                throw new IllegalArgumentException("Invalid cost allocation tag key: " + tagKey);
            }
            // AWS lowercases and underscores tag keys when building CUR columns:
            // "Environment" becomes resource_tags_user_environment.
            return "resource_tags_user_" + tagKey.toLowerCase().replaceAll("[^a-z0-9]", "_");
        }

        String column = groupBy == null ? null : GROUP_BY_COLUMNS.get(groupBy.toUpperCase());
        if (column == null) {
            throw new IllegalArgumentException("Unsupported CUR groupBy dimension: " + groupBy);
        }
        return column;
    }

    /**
     * An optional {@code AND column = 'value'} clause, or nothing.
     *
     * <p>Treats null, blank and "ALL" as absent, matching how the Cost Explorer path
     * decides whether to attach a dimension filter — a caller passing "ALL" means
     * unfiltered, not a service literally named ALL.
     */
    private String dimensionFilter(String column, String value) {
        if (value == null || value.isBlank() || "ALL".equalsIgnoreCase(value)) {
            return "";
        }
        return " AND " + column + " = " + sqlLiteral(value);
    }

    /**
     * Renders a string as a SQL literal.
     *
     * <p>Values here are service and region names that arrive as query parameters from
     * the browser. They land in value position, not identifier position, so doubling
     * the quote is the correct escape — unlike a column name, which is whitelisted
     * instead because quoting cannot make arbitrary input safe there.
     */
    private String sqlLiteral(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private String blankTo(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }

    private double parseCost(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0.0;
        }
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            logger.warn("[CUR] Unparseable cost value '{}' — treating as 0", raw);
            return 0.0;
        }
    }
}
