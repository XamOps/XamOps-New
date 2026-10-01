package com.xammer.cloud.service.cur;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.CurSource;
import com.xammer.cloud.util.PriceVolumeSplit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Metrics that exist only in a CUR — the columns sitting in the same rows the cost
 * widgets already read, which the Cost Explorer API cannot express at all.
 *
 * <p>Every method here degrades rather than fails when a column is absent. Optional
 * column families are genuinely optional: {@code discount/*} and
 * {@code lineItem/NetUnblendedCost} exist only for accounts with an EDP or private
 * pricing agreement, {@code splitLineItem/*} only when the report was created with
 * SPLIT_COST_ALLOCATION_DATA, and a customer's existing CUR may have neither. The
 * caller gets an empty or partial result and hides the widget, rather than a 500.
 */
@Service
public class CurAdvancedMetricsService {

    private static final Logger logger = LoggerFactory.getLogger(CurAdvancedMetricsService.class);

    /**
     * Ceiling on resources returned in one response.
     *
     * <p>Grouping by resource id is by far the widest aggregation here — an account with
     * heavy S3 or Lambda use produces thousands of groups — and every one of them crosses
     * the wire and renders. Ordered by cost, the tail beyond this is rounding; the true
     * total travels separately so nothing is misreported.
     */
    private static final int MAX_RESOURCES = 500;

    /**
     * How many offenders are listed inside one service.
     *
     * <p>Small on purpose. A flat list of every untagged resource is a wall rather than a
     * work queue — the reader cannot act on a thousand rows, and paging through them finds
     * nothing the first page did not already say. The service totals beside them are
     * computed over every resource regardless, so nothing is hidden, only folded.
     */
    private static final int UNTAGGED_PER_SERVICE = 10;

    /**
     * How many of the costliest resources the concentration figure covers.
     *
     * <p>Reported so the caller can say what fraction of the problem the top of the list
     * is. On a real bill it is most of it, which is what turns "1,826 untagged resources"
     * from a hopeless backlog into an afternoon's work.
     */
    private static final int CONCENTRATION_HEAD = 20;

    /** Ceiling on a bulk export, which is read by a script rather than by a person. */
    private static final int MAX_UNTAGGED_EXPORT = 5000;

    /** Ceiling on distinct tag values. A free-text tag can have thousands. */
    private static final int MAX_TAG_VALUES = 50;

    /**
     * How many resource-and-route flows are listed inside one network category.
     *
     * <p>"Which resource is sending what, where" is the whole point of the drill-down, and
     * on a real account the answer is a handful of NAT gateways, one chatty database and a
     * long tail of nothing. Higher than the per-service caps elsewhere because the grain is
     * finer — one resource talking to three regions is three rows — and because the views
     * above it are rolled up from these rows client-side, so a cap that is too tight would
     * make the rolled-up totals wrong rather than merely short.
     */
    private static final int TRANSFER_FLOWS_PER_CATEGORY = 40;

    /**
     * Below this a flow is not worth a row: it rounds to $0.00 on screen.
     *
     * <p>Named rather than inlined because it has to be applied in two places that must
     * agree — the rows returned and the count of how many exist. When they disagreed, the
     * footer said "top 9 of 10 — 100% of this category", which reads as arithmetic that does
     * not add up rather than as a sub-cent row being hidden.
     */
    private static final String TRANSFER_MIN_COST = "0.001";

    /**
     * Which usage types count as network charges.
     *
     * <p>Shared by the summary and the flow drill-down on purpose: a resource list that
     * filtered differently from the total above it would not add up to it, and the
     * discrepancy would be read as a bug in the numbers rather than in the predicate.
     *
     * <p>{@code %Bytes%} catches most of it, including Transit Gateway and VPC endpoint
     * processing. The named patterns are for the hourly components, which carry no "Bytes"
     * and are a real part of what those things cost.
     */
    private static final String NETWORK_USAGE_PREDICATE =
            "(line_item_usage_type LIKE '%DataTransfer%'"
            + " OR line_item_usage_type LIKE '%NatGateway%'"
            + " OR line_item_usage_type LIKE '%VpcEndpoint%'"
            + " OR line_item_usage_type LIKE '%PrivateLink%'"
            + " OR line_item_usage_type LIKE '%TransitGateway%'"
            + " OR line_item_usage_type LIKE '%Bytes%')";

    /** What spend with no value for the tag is filed under. */
    private static final String UNASSIGNED = "(unassigned)";

    private final CurAthenaQueryService athena;
    private final CurTagColumns tagColumns;
    private final CurColumnCatalog columnCatalog;

    public CurAdvancedMetricsService(CurAthenaQueryService athena, CurTagColumns tagColumns,
                                    CurColumnCatalog columnCatalog) {
        this.athena = athena;
        this.tagColumns = tagColumns;
        this.columnCatalog = columnCatalog;
    }

    // ── Effective savings rate ──────────────────────────────────────────────────

    /**
     * What the account actually paid against what the same usage would have cost at
     * public on-demand rates.
     *
     * <p>The headline FinOps KPI, and not derivable from Cost Explorer: CE reports
     * what you were charged but never what the list price would have been.
     * {@code pricing/PublicOnDemandCost} is that counterfactual, on every line.
     *
     * <p>Amortized rather than unblended cost is the numerator on purpose. Unblended
     * puts an RI or Savings Plan upfront fee entirely on the day it was charged, which
     * would show a savings rate far below zero that day and inflated for the rest of
     * the term. The amortized figure spreads it, which is the only way the ratio means
     * anything over a partial period.
     */
    public SavingsRate getEffectiveSavingsRate(CurSource source, CloudAccount account,
                                               LocalDate start, LocalDate end) {
        String amortized = amortizedCostExpr(source);
        String onDemand = CurSql.numericExpr(source, "pricing_public_on_demand_cost");

        String sql = "SELECT SUM(" + amortized + ") AS amortized,"
                + " SUM(" + onDemand + ") AS on_demand,"
                + " SUM(" + CurSql.numericExpr(source, "line_item_unblended_cost") + ") AS unblended"
                + " FROM " + source.qualifiedTable()
                + " WHERE " + accountPredicate(account)
                + " AND " + CurSql.partitionPredicate(source, start, end)
                + " AND " + CurSql.usageDatePredicate(source, start, end)
                // On-demand cost is only populated for usage lines. Including tax, fees and
                // credits would put charges in the numerator with no counterpart in the
                // denominator and understate the rate.
                + " AND line_item_line_item_type IN ("
                + "'Usage','DiscountedUsage','SavingsPlanCoveredUsage','SavingsPlanNegation')";

        List<Map<String, String>> rows = athena.query(source, account, sql);
        if (rows.isEmpty()) {
            return new SavingsRate(0, 0, 0, 0);
        }

        double amortizedCost = parse(rows.get(0).get("amortized"));
        double onDemandCost = parse(rows.get(0).get("on_demand"));
        double unblendedCost = parse(rows.get(0).get("unblended"));

        double rate = onDemandCost > 0 ? 1.0 - (amortizedCost / onDemandCost) : 0.0;
        return new SavingsRate(amortizedCost, onDemandCost, onDemandCost - amortizedCost, rate);
    }

    /**
     * Amortized cost: the true daily cost of a commitment, spread across its term.
     *
     * <p>Built as a CASE over {@code lineItem/LineItemType} because each commitment
     * type records its amortized value in a different column, and the raw fee rows
     * have to be zeroed or they would be counted twice — once as the fee and again as
     * the discounted usage they paid for.
     */
    static String amortizedCostExpr(CurSource source) {
        String effective = CurSql.numericExpr(source, "reservation_effective_cost");
        String spEffective = CurSql.numericExpr(source, "savings_plan_savings_plan_effective_cost");
        String unblended = CurSql.numericExpr(source, "line_item_unblended_cost");

        return "CASE"
                // Usage covered by a reservation: charge the reservation's effective rate.
                + " WHEN line_item_line_item_type = 'DiscountedUsage' THEN " + effective
                // Usage covered by a Savings Plan: likewise.
                + " WHEN line_item_line_item_type = 'SavingsPlanCoveredUsage' THEN " + spEffective
                // The recurring/upfront fee rows themselves are already represented by the
                // effective costs above, so they contribute nothing.
                + " WHEN line_item_line_item_type IN ('RIFee','SavingsPlanRecurringFee',"
                + "'SavingsPlanUpfrontFee','SavingsPlanNegation') THEN 0"
                + " ELSE " + unblended
                + " END";
    }

    // ── Price vs volume ─────────────────────────────────────────────────────────

    /**
     * Usage quantity alongside cost, per service, for two periods.
     *
     * <p>Answers "cost rose 20% — was that more usage, or a higher rate?", which no
     * Cost Explorer aggregate can: CE returns dollars, and the usage quantity it does
     * expose cannot be grouped the same way. Splitting a change into price and volume
     * is the whole point of having the CUR.
     */
    public List<PriceVolumeChange> getPriceVsVolume(CurSource source, CloudAccount account,
                                                    LocalDate currentStart, LocalDate currentEnd,
                                                    LocalDate priorStart, LocalDate priorEnd) {

        Map<String, double[]> current = usageAndCostByService(source, account, currentStart, currentEnd);
        Map<String, double[]> prior = usageAndCostByService(source, account, priorStart, priorEnd);

        List<PriceVolumeChange> changes = new ArrayList<>();
        for (Map.Entry<String, double[]> entry : current.entrySet()) {
            String service = entry.getKey();
            double curCost = entry.getValue()[0];
            double curQty = entry.getValue()[1];

            double[] priorValues = prior.getOrDefault(service, new double[]{0, 0});
            double priorCost = priorValues[0];
            double priorQty = priorValues[1];

            // Shared with the Azure and GCP paths and with the per-resource breakdown, so all four
            // report the same split — including the new-service case the other two clouds used to get
            // backwards. See PriceVolumeSplit for the decomposition and why price is the residual.
            PriceVolumeSplit.Effects effects =
                    PriceVolumeSplit.of(priorCost, curCost, priorQty, curQty);

            changes.add(new PriceVolumeChange(service, priorCost, curCost, curCost - priorCost,
                    priorQty, curQty, effects.priceEffect(), effects.volumeEffect()));
        }

        changes.sort((a, b) -> Double.compare(Math.abs(b.costChange()), Math.abs(a.costChange())));
        return changes;
    }

    private Map<String, double[]> usageAndCostByService(CurSource source, CloudAccount account,
                                                        LocalDate start, LocalDate end) {
        String sql = "SELECT product_product_name AS svc,"
                + " SUM(" + CurSql.numericExpr(source, "line_item_unblended_cost") + ") AS cost,"
                + " SUM(" + CurSql.numericExpr(source, "line_item_usage_amount") + ") AS qty"
                + " FROM " + source.qualifiedTable()
                + " WHERE " + accountPredicate(account)
                + " AND " + CurSql.partitionPredicate(source, start, end)
                + " AND " + CurSql.usageDatePredicate(source, start, end)
                // Usage lines only: a tax or credit row has a cost but no comparable
                // quantity, and averaging it into a unit rate is meaningless.
                + " AND line_item_line_item_type = 'Usage'"
                + " GROUP BY product_product_name";

        Map<String, double[]> byService = new java.util.LinkedHashMap<>();
        for (Map<String, String> row : athena.query(source, account, sql)) {
            String svc = row.get("svc");
            if (svc == null || svc.isBlank()) {
                svc = "Other";
            }
            byService.put(svc, new double[]{parse(row.get("cost")), parse(row.get("qty"))});
        }
        return byService;
    }

    // ── One resource, in depth ──────────────────────────────────────────────────

    /**
     * Everything the resource drill-down needs for one resource.
     *
     * <p>Two scans rather than one. The line-item comparison needs both months grouped by usage type;
     * the daily series needs the current month grouped by day. Combining them would group by usage type
     * <em>and</em> day, multiplying the rows by thirty for an answer that is summed back down — and
     * Athena bills per byte scanned, so the wider grouping is the more expensive shape.
     *
     * <p>Both are scoped to a single {@code line_item_resource_id}, which is the narrowest predicate in
     * this class. The cost of the drill-down is therefore roughly independent of how large the account
     * is, which is what makes it safe to open on demand rather than precomputing it.
     *
     * @param resourceId the id as the report records it, or empty for the resource-less charges the
     *                   table groups separately — those have no meaningful per-resource story, so the
     *                   caller does not offer the drill-down for them
     */
    public ResourceChange getResourceChange(CurSource source, CloudAccount account, String resourceId,
                                            String service, String region,
                                            LocalDate currentStart, LocalDate currentEnd,
                                            LocalDate priorStart, LocalDate priorEnd,
                                            boolean comparePrior) {

        // Scoped to the row that opened this, not to the resource id alone. See scopePredicate.
        String scope = scopePredicate(resourceId, service, region);

        Map<String, double[]> current = usageAndCostByLineItem(source, account, scope,
                currentStart, currentEnd);
        /*
         * The earlier month is optional.
         *
         * A CUR begins at the month it was created and never backfills, so on a report younger than two
         * months the prior month simply is not in it. Requiring it made this whole endpoint 409 — the
         * panel opened onto "no detail available" even though the current month's lines and daily series
         * were sitting right there, queryable. The comparison degrades; the rest does not.
         *
         * Skipped rather than queried-and-empty, because an empty prior month and an absent one are
         * different facts: zero would make PriceVolumeSplit read the resource as brand new and report its
         * entire cost as "used more", which is a statement about the report's age dressed up as a
         * statement about the infrastructure.
         */
        Map<String, double[]> prior = comparePrior
                ? usageAndCostByLineItem(source, account, scope, priorStart, priorEnd)
                : Map.of();

        // Descriptors are carried alongside the figures so a usage type present in only one of the two
        // months still renders with its unit and description rather than as a bare code.
        Map<String, String[]> descriptors = new LinkedHashMap<>();
        if (comparePrior) {
            descriptors.putAll(lineDescriptors(source, account, scope, priorStart, priorEnd));
        }
        // Current month last, so where the two disagree the newer wording wins.
        descriptors.putAll(lineDescriptors(source, account, scope, currentStart, currentEnd));

        Set<String> usageTypes = new java.util.LinkedHashSet<>(current.keySet());
        usageTypes.addAll(prior.keySet());

        List<ResourceLineChange> lines = new ArrayList<>();
        double priorCost = 0;
        double currentCost = 0;
        double priorQty = 0;
        double currentQty = 0;

        for (String usageType : usageTypes) {
            double[] cur = current.getOrDefault(usageType, new double[]{0, 0});
            double[] pri = prior.getOrDefault(usageType, new double[]{0, 0});
            priorCost += pri[0];
            currentCost += cur[0];
            priorQty += pri[1];
            currentQty += cur[1];

            // Per line, the split is exact rather than indicative: one usage type bills in one unit, so
            // cost ÷ quantity is a real unit rate. That is the whole reason this breakdown exists — the
            // service-level split has to blend units and can only point at a direction.
            PriceVolumeSplit.Effects effects = PriceVolumeSplit.of(pri[0], cur[0], pri[1], cur[1]);
            String[] descriptor = descriptors.getOrDefault(usageType, new String[]{"", ""});
            lines.add(new ResourceLineChange(
                    blankTo(descriptor[0], usageType),
                    usageType,
                    blankTo(descriptor[1], ""),
                    pri[0], cur[0], cur[0] - pri[0],
                    pri[1], cur[1],
                    effects.priceEffect(), effects.volumeEffect()));
        }
        lines.sort(Comparator.comparingDouble((ResourceLineChange l) -> -Math.abs(l.costChange())));

        PriceVolumeSplit.Effects total = PriceVolumeSplit.of(priorCost, currentCost, priorQty, currentQty);
        ResourceIdentity identity = resourceIdentity(source, account, scope, currentStart, currentEnd,
                comparePrior ? priorStart : currentStart, comparePrior ? priorEnd : currentEnd);

        return new ResourceChange(resourceId, identity.name(), identity.service(), identity.region(),
                identity.instanceType(),
                priorCost, currentCost, currentCost - priorCost,
                priorQty, currentQty,
                total.priceEffect(), total.volumeEffect(),
                comparePrior,
                List.copyOf(lines),
                dailyCost(source, account, scope, currentStart, currentEnd));
    }

    /** Cost and usage per usage type for one resource over one period. */
    private Map<String, double[]> usageAndCostByLineItem(CurSource source, CloudAccount account,
                                                         String scope,
                                                         LocalDate start, LocalDate end) {
        String sql = "SELECT line_item_usage_type AS usage_type,"
                + " SUM(" + CurSql.numericExpr(source, "line_item_unblended_cost") + ") AS cost,"
                + " SUM(" + CurSql.numericExpr(source, "line_item_usage_amount") + ") AS qty"
                + " FROM " + source.qualifiedTable()
                + " WHERE " + accountPredicate(account)
                + " AND " + scope
                + " AND " + CurSql.partitionPredicate(source, start, end)
                + " AND " + CurSql.usageDatePredicate(source, start, end)
                // Usage lines only, matching the service-level comparison: a tax or credit row has a
                // cost but no comparable quantity, and averaging it into a unit rate is meaningless.
                + " AND line_item_line_item_type = 'Usage'"
                + " GROUP BY line_item_usage_type";

        Map<String, double[]> byUsageType = new LinkedHashMap<>();
        for (Map<String, String> row : athena.query(source, account, sql)) {
            String usageType = blankTo(row.get("usage_type"), "Other");
            byUsageType.put(usageType, new double[]{parse(row.get("cost")), parse(row.get("qty"))});
        }
        return byUsageType;
    }

    /** Description and unit per usage type, so a line reads as words rather than as a code. */
    private Map<String, String[]> lineDescriptors(CurSource source, CloudAccount account,
                                                  String scope, LocalDate start, LocalDate end) {
        String sql = "SELECT line_item_usage_type AS usage_type,"
                + " MAX(line_item_line_item_description) AS description,"
                + " MAX(pricing_unit) AS unit"
                + " FROM " + source.qualifiedTable()
                + " WHERE " + accountPredicate(account)
                + " AND " + scope
                + " AND " + CurSql.partitionPredicate(source, start, end)
                + " AND " + CurSql.usageDatePredicate(source, start, end)
                + " AND line_item_line_item_type = 'Usage'"
                + " GROUP BY line_item_usage_type";

        Map<String, String[]> descriptors = new LinkedHashMap<>();
        for (Map<String, String> row : athena.query(source, account, sql)) {
            descriptors.put(blankTo(row.get("usage_type"), "Other"),
                    new String[]{blankTo(row.get("description"), ""), blankTo(row.get("unit"), "")});
        }
        return descriptors;
    }

    /** What the resource is: name, service, region, instance type. */
    private record ResourceIdentity(String name, String service, String region, String instanceType) {
    }

    /**
     * The resource's descriptors, preferring the current month and falling back to the prior one.
     *
     * <p>The fallback matters: a resource deleted partway through the comparison has no current-month
     * rows at all, and it is exactly the resource a reader most wants to open — its cost went to zero
     * and they want to know what it was.
     */
    private ResourceIdentity resourceIdentity(CurSource source, CloudAccount account, String scope,
                                              LocalDate currentStart, LocalDate currentEnd,
                                              LocalDate priorStart, LocalDate priorEnd) {
        ResourceIdentity current = readIdentity(source, account, scope, currentStart, currentEnd);
        if (!current.service().isBlank()) {
            return current;
        }
        return readIdentity(source, account, scope, priorStart, priorEnd);
    }

    private ResourceIdentity readIdentity(CurSource source, CloudAccount account, String scope,
                                          LocalDate start, LocalDate end) {
        String nameExpr = tagColumns.resourceNameExpr(source, account).orElse(null);
        String nameSelect = nameExpr == null ? "" : " MAX(" + nameExpr + ") AS resource_name,";

        String sql = "SELECT" + nameSelect
                + " MAX(product_product_name) AS service,"
                + " MAX(product_region) AS region,"
                + " MAX(product_instance_type) AS instance_type"
                + " FROM " + source.qualifiedTable()
                + " WHERE " + accountPredicate(account)
                + " AND " + scope
                + " AND " + CurSql.partitionPredicate(source, start, end)
                + " AND " + CurSql.usageDatePredicate(source, start, end);

        for (Map<String, String> row : athena.query(source, account, sql)) {
            return new ResourceIdentity(
                    blankTo(row.get("resource_name"), ""),
                    blankTo(row.get("service"), ""),
                    blankTo(row.get("region"), "Global"),
                    blankTo(row.get("instance_type"), ""));
        }
        return new ResourceIdentity("", "", "Global", "");
    }

    /**
     * One resource's cost per day over the period.
     *
     * <p>Every line item type, unlike the comparison above — this is the resource's spend, and a reader
     * looking at the shape of a change wants the day a charge landed whether or not it carried a
     * quantity.
     */
    private List<DailyCost> dailyCost(CurSource source, CloudAccount account, String scope,
                                      LocalDate start, LocalDate end) {
        String day = CurSql.usageDateExpr(source);
        String sql = "SELECT CAST(" + day + " AS DATE) AS day,"
                + " SUM(" + CurSql.numericExpr(source, "line_item_unblended_cost") + ") AS cost"
                + " FROM " + source.qualifiedTable()
                + " WHERE " + accountPredicate(account)
                + " AND " + scope
                + " AND " + CurSql.partitionPredicate(source, start, end)
                + " AND " + CurSql.usageDatePredicate(source, start, end)
                + " GROUP BY CAST(" + day + " AS DATE)"
                + " ORDER BY day";

        List<DailyCost> daily = new ArrayList<>();
        for (Map<String, String> row : athena.query(source, account, sql)) {
            String date = blankTo(row.get("day"), "");
            if (!date.isBlank()) {
                // Athena returns a DATE as "2026-08-14"; trimmed to ten characters in case a driver
                // renders it with a time component, so the axis labels stay dates.
                daily.add(new DailyCost(date.length() > 10 ? date.substring(0, 10) : date,
                        parse(row.get("cost"))));
            }
        }
        return List.copyOf(daily);
    }

    /**
     * Scopes a query to one resource.
     *
     * <p>Parameterised by literal rather than by a bind variable because this class builds Athena SQL as
     * strings throughout, so the id is quoted and any embedded quote doubled. A resource id is an ARN
     * the customer does not choose freely, but it arrives from a query parameter, and an id that
     * terminated the string early would be a SQL injection into the tenant's own data.
     */
    private String resourcePredicate(String resourceId) {
        return "line_item_resource_id = " + literal(resourceId);
    }

    /**
     * Narrows the drill-down to the same slice the table row represents.
     *
     * <p>{@link #getTopResources} groups by {@code (resource_id, product_product_name,
     * product_region)}, so one resource spanning two services or two regions is several rows, each
     * showing part of its cost. The drill-down originally filtered on the resource id alone and
     * therefore reported the resource's <em>whole</em> cost — a larger number than the row that opened
     * it, with nothing on screen to explain the gap. Observed as a row reading $76.42 opening a panel
     * whose trend summed to $86.01.
     *
     * <p>Scoping to the row is the right way round of the two. The panel exists to explain the figure
     * the reader just clicked, and a resource that also bills under another service has its own row
     * there with its own panel. Answering a question the reader did not ask, in a number that
     * contradicts the one they did, is worse than answering a narrower one.
     *
     * <p>Both are optional: a caller that does not know the row's service and region gets the
     * resource-wide figures, which is the correct degraded answer rather than an empty one.
     */
    private String scopePredicate(String resourceId, String service, String region) {
        StringBuilder predicate = new StringBuilder(resourcePredicate(resourceId));
        if (service != null && !service.isBlank()) {
            predicate.append(" AND product_product_name = ").append(literal(service));
        }
        if (region != null && !region.isBlank()) {
            // "Global" is this section's own word for a row the report gave no region, not a value the
            // report contains — so it has to be matched as the absence it stands for.
            predicate.append("Global".equals(region)
                    ? " AND (product_region IS NULL OR product_region = '')"
                    : " AND product_region = " + literal(region));
        }
        return predicate.toString();
    }

    /**
     * A single-quoted SQL literal with embedded quotes doubled.
     *
     * <p>This class builds Athena SQL as strings, so values reaching it from a query parameter have to
     * be escaped here. A resource id is an ARN and a service name comes from the report, but both
     * arrive over HTTP, and one that terminated the string early would be an injection into the
     * tenant's own billing data.
     */
    private String literal(String value) {
        return "'" + (value == null ? "" : value.replace("'", "''")) + "'";
    }

    // ── Charges by service, invoice style ───────────────────────────────────────

    /**
     * Line items as the AWS bill presents them: description, usage quantity, and amount.
     *
     * <p>Reproduces the "Charges by service" section of the AWS invoice, which Cost
     * Explorer cannot: CE reports dollars, and the usage quantity it exposes cannot be
     * lined up against the same rows. Here quantity and cost sit on every line already.
     *
     * <p>Rows are grouped by service, description and unit rather than by service alone,
     * because that is the granularity a bill is read at — "EBS gp3 storage, 1,024 GB-Mo,
     * $81.92" answers a question that "EBS, $340" does not.
     *
     * <p>Usable on the CURRENT month, unlike the period comparisons: it describes what has
     * been billed so far rather than comparing two spans of different length.
     */
    public List<ServiceCharge> getChargesByService(CurSource source, CloudAccount account,
                                                   LocalDate start, LocalDate end) {
        String cost = CurSql.numericExpr(source, "line_item_unblended_cost");
        String qty = CurSql.numericExpr(source, "line_item_usage_amount");

        String sql = "SELECT product_product_name AS service,"
                + " product_region AS region,"
                + " line_item_line_item_description AS description,"
                + " line_item_usage_type AS usage_type,"
                + " pricing_unit AS unit,"
                + " SUM(" + qty + ") AS quantity,"
                + " SUM(" + cost + ") AS cost"
                + " FROM " + source.qualifiedTable()
                + " WHERE " + accountPredicate(account)
                + " AND " + CurSql.partitionPredicate(source, start, end)
                + " AND " + CurSql.usageDatePredicate(source, start, end)
                + " GROUP BY product_product_name, product_region,"
                + " line_item_line_item_description, line_item_usage_type, pricing_unit"
                // A bill lists what was charged, so rows that net to nothing are dropped —
                // but credits and refunds are negative and must survive the filter.
                + " HAVING ABS(SUM(" + cost + ")) > 0.001"
                + " ORDER BY cost DESC";

        List<ServiceCharge> charges = new ArrayList<>();
        for (Map<String, String> row : athena.query(source, account, sql)) {
            charges.add(new ServiceCharge(
                    blankTo(row.get("service"), "Other"),
                    // Region-less charges — tax, support, some Marketplace items — are what
                    // the AWS invoice files under "Global", so the same label is used here.
                    blankTo(row.get("region"), "Global"),
                    // Description is blank on some line types (tax, certain fees); the usage
                    // type is the next most specific thing a reader can act on.
                    blankTo(row.get("description"), blankTo(row.get("usage_type"), "—")),
                    blankTo(row.get("usage_type"), ""),
                    blankTo(row.get("unit"), ""),
                    parse(row.get("quantity")),
                    parse(row.get("cost"))));
        }

        logger.info("✅ [CUR] Charges by service — {} rows for account {} ({} to {})",
                charges.size(), account.getAwsAccountId(), start, end);
        return charges;
    }

    // ── Cost per resource ───────────────────────────────────────────────────────

    /**
     * What each individual resource cost — the bucket, the instance, the volume.
     *
     * <p>No Cost Explorer equivalent exists. Its resource-level view covers EC2 alone and
     * retains fourteen days, so "which of my buckets is the $2,527" has, until now, been
     * unanswerable from the product: the service breakdown stops at "S3".
     *
     * <p>Charges with no resource — tax, support, some transfer — come back as rows with an
     * empty id rather than being dropped. Filtering them would make this section's total
     * disagree with the invoice, which is the same class of error as clamping the end date
     * to today: defensible-looking, and wrong by a wide margin.
     */
    public TopResources getTopResources(CurSource source, CloudAccount account,
                                        LocalDate start, LocalDate end) {
        String cost = CurSql.numericExpr(source, "line_item_unblended_cost");
        String resourceId = "COALESCE(NULLIF(line_item_resource_id, ''), '')";

        // Absent unless the Name tag reaches this report — see CurTagColumns. MAX because
        // the name has to survive the grouping; a resource renamed mid-month has two, and
        // either is a better label than its id.
        String nameExpr = tagColumns.resourceNameExpr(source, account).orElse(null);
        String nameInner = nameExpr == null ? "" : " MAX(" + nameExpr + ") AS resource_name,";
        String nameOuter = nameExpr == null ? "" : " resource_name,";

        // The window functions run after grouping and before the limit, so they describe
        // every resource in the month rather than the page being returned. That is what
        // lets the caller say "top 50 of 431, $2,367 in total" honestly — computing the
        // total from the returned rows alone would silently understate it by the tail.
        String sql = "SELECT resource_id," + nameOuter + " service, region, instance_type, cost,"
                + " total_cost, group_count FROM ("
                + "SELECT " + resourceId + " AS resource_id,"
                + nameInner
                + " product_product_name AS service,"
                + " product_region AS region,"
                + " MAX(product_instance_type) AS instance_type,"
                + " SUM(" + cost + ") AS cost,"
                + " SUM(SUM(" + cost + ")) OVER () AS total_cost,"
                + " COUNT(*) OVER () AS group_count"
                + " FROM " + source.qualifiedTable()
                + " WHERE " + accountPredicate(account)
                + " AND " + CurSql.partitionPredicate(source, start, end)
                + " AND " + CurSql.usageDatePredicate(source, start, end)
                + " GROUP BY " + resourceId + ", product_product_name, product_region"
                + ") ORDER BY cost DESC LIMIT " + MAX_RESOURCES;

        List<ResourceCost> resources = new ArrayList<>();
        double totalCost = 0;
        long groupCount = 0;
        for (Map<String, String> row : athena.query(source, account, sql)) {
            resources.add(new ResourceCost(
                    blankTo(row.get("resource_id"), ""),
                    blankTo(row.get("resource_name"), ""),
                    blankTo(row.get("service"), "Other"),
                    blankTo(row.get("region"), "Global"),
                    blankTo(row.get("instance_type"), ""),
                    parse(row.get("cost"))));
            // Identical on every row — the window is unpartitioned.
            totalCost = parse(row.get("total_cost"));
            groupCount = (long) parse(row.get("group_count"));
        }

        logger.info("✅ [CUR] Top resources — {} of {} rows, ${} total for account {} ({} to {})",
                resources.size(), groupCount, totalCost, account.getAwsAccountId(), start, end);
        return new TopResources(resources, totalCost, groupCount);
    }

    // ── Tag coverage and cost allocation ────────────────────────────────────────

    /**
     * How much of the bill can be attributed to an owner, and what cannot.
     *
     * <p>Deliberately measured in dollars rather than in resources. Counting resources
     * answers a question nobody is asking: an account with 1,944 untagged AMIs and one
     * untagged NAT gateway reports 0.1% compliance either way, but the AMIs cost cents and
     * the gateway costs $63 — so a resource count sends the reader to fix the wrong thing
     * first, every time. Ranked by spend, the list is a work queue.
     *
     * <p>Only tags the report actually carries are checked; see {@link CurTagColumns}. A
     * required tag that was never activated for cost allocation is reported as
     * unavailable rather than scored as universally missing, because the second is a claim
     * about the infrastructure and the truth is a setting in the billing console.
     *
     * @param requiredTags the tag keys an owner is expected to carry, in priority order
     */
    public TagCoverage getTagCoverage(CurSource source, CloudAccount account,
                                      LocalDate start, LocalDate end, List<String> requiredTags) {
        Map<String, String> exprs = tagColumns.exprsFor(source, account, requiredTags);
        List<String> missingFromReport = requiredTags.stream()
                .filter(key -> !exprs.containsKey(key))
                .toList();

        if (exprs.isEmpty()) {
            logger.info("[CUR] Tag coverage — none of {} are in the report for account {}",
                    requiredTags, account.getAwsAccountId());
            return new TagCoverage(List.of(), missingFromReport, 0, 0, List.of(), List.of(), 0,
                    CONCENTRATION_HEAD, 0, tagColumns.availableTagKeys(source, account));
        }

        String cost = CurSql.numericExpr(source, "line_item_unblended_cost");
        List<String> checked = List.copyOf(exprs.keySet());
        TagPredicates tags = tagPredicates(cost, checked, exprs);

        String nameExpr = tagColumns.resourceNameExpr(source, account).orElse(null);
        String nameInner = nameExpr == null ? "" : " MAX(" + nameExpr + ") AS resource_name,";
        String nameOuter = nameExpr == null ? "" : " resource_name,";
        String untagged = "SUM(" + cost + ") - " + tags.allocated();

        /*
         * One scan answers three questions at three grains, because the list of offenders
         * is not the deliverable — a thousand-row table is a wall, not a work queue.
         *
         *   - the whole account, from the unpartitioned windows;
         *   - every service, complete and never truncated, from the windows partitioned by
         *     service — so a service's total and count describe all of its resources even
         *     though only its worst few rows come back;
         *   - the worst few resources inside each service, from the per-service rank.
         *
         * The global rank rides along so the caller can say how concentrated the problem
         * is. That is the number that turns "1,826 untagged resources" from a hopeless
         * backlog into an afternoon: on a real bill the top twenty are most of the money.
         */
        String sql = "SELECT resource_id," + nameOuter + " service, region, cost, allocated,"
                + " total_cost, total_allocated, untagged_count,"
                + " service_untagged, service_untagged_count, global_rank,"
                + tagTotalsSelectList(checked.size())
                + " FROM ("
                + resourceLevelSelect(source, account, start, end, cost, nameInner, tags,
                        windowSelect(untagged))
                + ") WHERE service_rank <= " + UNTAGGED_PER_SERVICE
                + " OR global_rank <= " + CONCENTRATION_HEAD
                + " ORDER BY (cost - allocated) DESC";

        double totalCost = 0;
        double totalAllocated = 0;
        long untaggedCount = 0;
        double headCost = 0;
        double[] taggedByKey = new double[checked.size()];
        Map<String, UntaggedServiceBuilder> byService = new LinkedHashMap<>();

        for (Map<String, String> row : athena.query(source, account, sql)) {
            // Identical on every row — these windows are unpartitioned.
            totalCost = parse(row.get("total_cost"));
            totalAllocated = parse(row.get("total_allocated"));
            untaggedCount = (long) parse(row.get("untagged_count"));
            for (int i = 0; i < checked.size(); i++) {
                taggedByKey[i] = parse(row.get("total_tagged_" + i));
            }

            double unattributed = parse(row.get("cost")) - parse(row.get("allocated"));
            if (unattributed <= 0.0001) {
                continue;
            }
            if (parse(row.get("global_rank")) <= CONCENTRATION_HEAD) {
                headCost += unattributed;
            }

            String service = blankTo(row.get("service"), "Other");
            UntaggedServiceBuilder group = byService.computeIfAbsent(service, s ->
                    new UntaggedServiceBuilder(s, parse(row.get("service_untagged")),
                            (long) parse(row.get("service_untagged_count"))));
            group.resources().add(new UntaggedResource(
                    blankTo(row.get("resource_id"), ""),
                    blankTo(row.get("resource_name"), ""),
                    service,
                    blankTo(row.get("region"), "Global"),
                    unattributed,
                    missingTagsFor(row, checked)));
        }

        List<UntaggedService> services = byService.values().stream()
                .map(UntaggedServiceBuilder::build)
                .sorted((a, b) -> Double.compare(b.cost(), a.cost()))
                .toList();

        List<TagKeyCoverage> byKey = new ArrayList<>();
        for (int i = 0; i < checked.size(); i++) {
            byKey.add(new TagKeyCoverage(checked.get(i), taggedByKey[i], totalCost - taggedByKey[i]));
        }

        logger.info("✅ [CUR] Tag coverage — ${} of ${} attributable, {} resources across {}"
                        + " services with unattributed spend for account {} ({} to {})",
                totalAllocated, totalCost, untaggedCount, services.size(),
                account.getAwsAccountId(), start, end);
        return new TagCoverage(checked, missingFromReport, totalCost, totalAllocated, byKey,
                services, untaggedCount, CONCENTRATION_HEAD, headCost,
                tagColumns.availableTagKeys(source, account));
    }

    /**
     * Every resource with unattributed spend, for export.
     *
     * <p>The panel deliberately shows a handful; this is the same finding as a file, for
     * the case the list is genuinely worked through in bulk — fed to a tagging script
     * rather than read. Capped only to keep one query from returning a million rows.
     */
    public List<UntaggedResource> getAllUntaggedResources(CurSource source, CloudAccount account,
                                                          LocalDate start, LocalDate end,
                                                          List<String> requiredTags) {
        Map<String, String> exprs = tagColumns.exprsFor(source, account, requiredTags);
        if (exprs.isEmpty()) {
            return List.of();
        }

        String cost = CurSql.numericExpr(source, "line_item_unblended_cost");
        List<String> checked = List.copyOf(exprs.keySet());
        TagPredicates tags = tagPredicates(cost, checked, exprs);

        String nameExpr = tagColumns.resourceNameExpr(source, account).orElse(null);
        String nameInner = nameExpr == null ? "" : " MAX(" + nameExpr + ") AS resource_name,";
        String nameOuter = nameExpr == null ? "" : " resource_name,";

        String sql = "SELECT resource_id," + nameOuter + " service, region, cost, allocated,"
                + tagTotalsSelectList(checked.size())
                + " FROM ("
                + resourceLevelSelect(source, account, start, end, cost, nameInner, tags, "")
                + ") WHERE cost - allocated > 0.0001"
                + " ORDER BY (cost - allocated) DESC LIMIT " + MAX_UNTAGGED_EXPORT;

        List<UntaggedResource> all = new ArrayList<>();
        for (Map<String, String> row : athena.query(source, account, sql)) {
            all.add(new UntaggedResource(
                    blankTo(row.get("resource_id"), ""),
                    blankTo(row.get("resource_name"), ""),
                    blankTo(row.get("service"), "Other"),
                    blankTo(row.get("region"), "Global"),
                    parse(row.get("cost")) - parse(row.get("allocated")),
                    missingTagsFor(row, checked)));
        }

        logger.info("✅ [CUR] Untagged export — {} resources for account {} ({} to {})",
                all.size(), account.getAwsAccountId(), start, end);
        return all;
    }

    /**
     * Whether a line item carries each required tag, and all of them.
     *
     * <p>Cost is attributed at line-item level, not per resource: a tag added mid-month
     * leaves the same resource partly attributable and partly not, and rounding that to
     * "tagged" or "untagged" would misstate the very number this exists to report.
     */
    private TagPredicates tagPredicates(String cost, List<String> checked, Map<String, String> exprs) {
        StringBuilder perKey = new StringBuilder();
        StringBuilder allPresent = new StringBuilder();
        for (int i = 0; i < checked.size(); i++) {
            String present = "COALESCE(" + exprs.get(checked.get(i)) + ", '') <> ''";
            perKey.append(" SUM(CASE WHEN ").append(present).append(" THEN ").append(cost)
                    .append(" ELSE 0 END) AS tagged_").append(i).append(",")
                    .append(" SUM(SUM(CASE WHEN ").append(present).append(" THEN ").append(cost)
                    .append(" ELSE 0 END)) OVER () AS total_tagged_").append(i).append(",");
            allPresent.append(i == 0 ? "" : " AND ").append(present);
        }
        return new TagPredicates(
                "SUM(CASE WHEN " + allPresent + " THEN " + cost + " ELSE 0 END)",
                perKey.toString());
    }

    /**
     * One row per resource, with the account-wide totals riding along on each.
     *
     * @param extraWindows further window columns for the same grain, or "". They belong in
     *                     the SELECT list, which is why they are threaded through here
     *                     rather than concatenated onto the finished statement — appending
     *                     them lands them after the GROUP BY, where they are a syntax error.
     */
    private String resourceLevelSelect(CurSource source, CloudAccount account,
                                       LocalDate start, LocalDate end, String cost,
                                       String nameInner, TagPredicates tags, String extraWindows) {
        String resourceId = "COALESCE(NULLIF(line_item_resource_id, ''), '')";
        return "SELECT " + resourceId + " AS resource_id,"
                + nameInner
                + " product_product_name AS service,"
                + " product_region AS region,"
                + " SUM(" + cost + ") AS cost,"
                + " " + tags.allocated() + " AS allocated,"
                + " SUM(SUM(" + cost + ")) OVER () AS total_cost,"
                + " SUM(" + tags.allocated() + ") OVER () AS total_allocated,"
                // Counted across every resource, not just the rows returned: reporting the
                // length of a capped list as "N resources have untagged spend" states the
                // cap as a finding on any account that exceeds it.
                + " SUM(CASE WHEN SUM(" + cost + ") - " + tags.allocated() + " > 0.0001"
                + " THEN 1 ELSE 0 END) OVER () AS untagged_count,"
                // Trailing comma comes from the last per-key entry.
                + tags.perKeySelects().substring(0, tags.perKeySelects().length() - 1)
                + extraWindows
                + " FROM " + source.qualifiedTable()
                + " WHERE " + accountPredicate(account)
                + " AND " + CurSql.partitionPredicate(source, start, end)
                + " AND " + CurSql.usageDatePredicate(source, start, end)
                // Tax, support and other account-level charges belong to no resource and
                // can carry no tag. Scoring them as untagged would put a permanent,
                // unfixable floor under the number.
                + " AND " + resourceId + " <> ''"
                + " GROUP BY " + resourceId + ", product_product_name, product_region";
    }

    /**
     * Per-service totals and the two rankings, layered over the resource-level rows.
     *
     * <p>Partitioned by the column rather than by the {@code service} alias, which is not
     * in scope in the same SELECT that defines it.
     */
    private String windowSelect(String untagged) {
        return ", SUM(" + untagged + ") OVER (PARTITION BY product_product_name) AS service_untagged,"
                + " SUM(CASE WHEN " + untagged + " > 0.0001 THEN 1 ELSE 0 END)"
                + " OVER (PARTITION BY product_product_name) AS service_untagged_count,"
                + " ROW_NUMBER() OVER (PARTITION BY product_product_name"
                + " ORDER BY " + untagged + " DESC) AS service_rank,"
                + " ROW_NUMBER() OVER (ORDER BY " + untagged + " DESC) AS global_rank";
    }

    /** The outer query has to name every per-tag window column the inner one built. */
    private String tagTotalsSelectList(int keyCount) {
        StringBuilder columns = new StringBuilder();
        for (int i = 0; i < keyCount; i++) {
            columns.append(" tagged_").append(i).append(", total_tagged_").append(i).append(",");
        }
        // Trailing comma is consumed by the " FROM (" the caller appends after a column.
        return columns.substring(0, columns.length() - 1);
    }

    /** Which of the checked tags this resource is missing spend against. */
    private List<String> missingTagsFor(Map<String, String> row, List<String> checked) {
        double resourceCost = parse(row.get("cost"));
        List<String> missing = new ArrayList<>();
        for (int i = 0; i < checked.size(); i++) {
            if (parse(row.get("tagged_" + i)) < resourceCost - 0.0001) {
                missing.add(checked.get(i));
            }
        }
        return missing;
    }

    /**
     * Spend under one tag, by value — the chargeback view.
     *
     * <p>Untagged spend is returned as its own entry rather than dropped, because a
     * showback report that quietly omits what nobody owns adds up to less than the bill,
     * and the gap is exactly the number the finance team is looking for.
     */
    public List<TagValueCost> getCostByTagValue(CurSource source, CloudAccount account,
                                                LocalDate start, LocalDate end, String tagKey) {
        Map<String, String> exprs = tagColumns.exprsFor(source, account, List.of(tagKey));
        String expr = exprs.get(tagKey);
        if (expr == null) {
            return List.of();
        }

        String cost = CurSql.numericExpr(source, "line_item_unblended_cost");
        String value = "COALESCE(NULLIF(" + expr + ", ''), '" + UNASSIGNED + "')";

        String sql = "SELECT " + value + " AS tag_value,"
                + " SUM(" + cost + ") AS cost,"
                + " COUNT(DISTINCT line_item_resource_id) AS resources"
                + " FROM " + source.qualifiedTable()
                + " WHERE " + accountPredicate(account)
                + " AND " + CurSql.partitionPredicate(source, start, end)
                + " AND " + CurSql.usageDatePredicate(source, start, end)
                // Same universe as getTagCoverage, and for the same reason: tax and support
                // belong to no resource and can carry no tag, so including them would file
                // charges under "(unassigned)" that no amount of tagging could ever move.
                // It also keeps the two halves of the panel adding up to the same total —
                // one showing $2,443 beside the other's $2,361 reads as a bug, and is one.
                + " AND COALESCE(NULLIF(line_item_resource_id, ''), '') <> ''"
                + " GROUP BY " + value
                + " ORDER BY cost DESC LIMIT " + MAX_TAG_VALUES;

        List<TagValueCost> values = new ArrayList<>();
        for (Map<String, String> row : athena.query(source, account, sql)) {
            values.add(new TagValueCost(
                    blankTo(row.get("tag_value"), UNASSIGNED),
                    parse(row.get("cost")),
                    (long) parse(row.get("resources"))));
        }

        logger.info("✅ [CUR] Cost by tag '{}' — {} values for account {} ({} to {})",
                tagKey, values.size(), account.getAwsAccountId(), start, end);
        return values;
    }

    // ── Data transfer and network costs ─────────────────────────────────────────

    /**
     * Network spend, split into the categories that actually drive it.
     *
     * <p>Routinely the largest cost nobody can see: a service breakdown files NAT gateway
     * charges under EC2 and cross-AZ traffic under whichever service moved the bytes, so
     * the total never appears anywhere as a single number.
     *
     * <p>NAT Gateway deliberately includes its HOURLY fee alongside data processing. The
     * two are billed separately and the hourly one is the smaller — on this account
     * $40.32 in hours against $172.16 processed — so reporting only the hours (as a
     * service breakdown does) understates a NAT gateway roughly five-fold. The category is
     * "what this gateway costs", not "bytes moved".
     *
     * <h2>Why a category total on its own is not enough</h2>
     * "Inter-region: $2.44" is a fact nobody can act on. Acting on it needs two more
     * things, and the report is the only place either exists:
     *
     * <ul>
     *   <li><b>Where the traffic went.</b> {@code product_from_location} and
     *       {@code product_to_location} name the endpoints of every charged transfer, so
     *       $2.44 becomes "Mumbai → N. Virginia, 29 GB" — a route someone can move,
     *       cache or stop.</li>
     *   <li><b>Which resource moved it.</b> {@code line_item_resource_id} attributes the
     *       charge to a specific gateway, bucket or instance. A category total says the
     *       account has a cross-AZ problem; the resource says which database to move.</li>
     * </ul>
     *
     * <p>Neither is useful on its own, which is why {@link TransferFlow} carries both on one
     * row: "Mumbai → N. Virginia, $2.44" does not say what to change, and "nat-0a1b, $344"
     * does not say where its traffic is going. A flow is the smallest thing the report can
     * describe that names a resource, a route and a price together, so it is the grain the
     * drill-down is built on and the coarser views are rolled up from it.
     *
     * <h2>What the report cannot say</h2>
     * A line item names <em>one</em> resource — the one being charged — so a flow is
     * "this resource, to that region", never "this resource to that resource". The peer at
     * the far end is not in a CUR at any grain; identifying it needs VPC Flow Logs, which
     * are a different data source with a different price. The UI says so rather than
     * implying the missing half is a loading state.
     *
     * <p>Both columns are optional in practice — Data Exports is created with a column
     * selection, and an existing table XamOps did not provision may have neither — so each
     * is probed for and the corresponding depth is reported as unavailable rather than
     * faked. See {@link CurColumnCatalog} for why a missing column cannot simply be
     * selected and ignored. Where the location columns are missing the route is recovered
     * from the usage type instead ({@link CurNetworkUsageType}), which keeps inter-region
     * and egress routes working on a report that does not carry them.
     *
     * <p>Two scans, not one. Flow grain cannot also serve the invoice-level summary: a
     * report with resource ids off collapses every flow into one row per category, and the
     * summary has to stay correct in that case. Both are held for six hours by
     * {@code CurInsightCache}, so the second scan is paid once per account per period
     * rather than once per page view.
     */
    public DataTransferBreakdown getDataTransferCosts(CurSource source, CloudAccount account,
                                                      LocalDate start, LocalDate end) {
        String fromExpr = locationExpr(source, account, "from");
        String toExpr = locationExpr(source, account, "to");
        boolean routeData = fromExpr != null && toExpr != null;

        List<TransferCharge> charges =
                transferCharges(source, account, start, end, fromExpr, toExpr);

        // Gated on the column existing at all, not on resourceIdsEnabled: a report whose
        // ids are blank yields one "(no resource id)" row per category, which is a true
        // statement about the report, whereas selecting an absent column fails the query.
        boolean resourceData = columnCatalog.has(source, account, "line_item_resource_id");
        List<TransferFlow> flows = resourceData
                ? transferFlows(source, account, start, end, fromExpr, toExpr)
                : List.of();

        logger.info("✅ [CUR] Data transfer — {} charge rows, {} flows for account {}"
                        + " ({} to {}), routeColumns={}, resourceIds={}",
                charges.size(), flows.size(), account.getAwsAccountId(), start, end,
                routeData, resourceData);
        return new DataTransferBreakdown(charges, flows, routeData, resourceData);
    }

    /**
     * Network charges at billing grain, with the route attached when the report knows it.
     *
     * <p>Region and route join the grouping rather than arriving as a second query: they
     * are low-cardinality — a location is a region or an edge, not a resource — so the row
     * count grows by a small factor and the section still renders from one payload.
     */
    private List<TransferCharge> transferCharges(CurSource source, CloudAccount account,
                                                 LocalDate start, LocalDate end,
                                                 String fromExpr, String toExpr) {
        String cost = CurSql.numericExpr(source, "line_item_unblended_cost");
        String qty = CurSql.numericExpr(source, "line_item_usage_amount");
        String category = networkCategoryExpr();

        String routeSelect = (fromExpr == null ? "" : " " + fromExpr + " AS from_location,")
                + (toExpr == null ? "" : " " + toExpr + " AS to_location,");
        String routeGroup = (fromExpr == null ? "" : ", " + fromExpr)
                + (toExpr == null ? "" : ", " + toExpr);

        String sql = "SELECT " + category + " AS category,"
                + " product_product_name AS service,"
                + " line_item_usage_type AS usage_type,"
                + " pricing_unit AS unit,"
                + " product_region AS region,"
                + routeSelect
                + " SUM(" + qty + ") AS quantity,"
                + " SUM(" + cost + ") AS cost"
                + " FROM " + source.qualifiedTable()
                + " WHERE " + accountPredicate(account)
                + " AND " + CurSql.partitionPredicate(source, start, end)
                + " AND " + CurSql.usageDatePredicate(source, start, end)
                + " AND " + NETWORK_USAGE_PREDICATE
                + " GROUP BY " + category + ", product_product_name,"
                + " line_item_usage_type, pricing_unit, product_region" + routeGroup
                // Ingress is almost always free; a long tail of $0.00 rows would bury the
                // handful that cost real money.
                + " HAVING SUM(" + cost + ") > 0.001"
                + " ORDER BY cost DESC";

        List<TransferCharge> charges = new ArrayList<>();
        for (Map<String, String> row : athena.query(source, account, sql)) {
            String usageType = blankTo(row.get("usage_type"), "");
            Route route = resolveRoute(usageType,
                    blankTo(row.get("from_location"), ""), blankTo(row.get("to_location"), ""));
            charges.add(new TransferCharge(
                    blankTo(row.get("category"), "Other network"),
                    blankTo(row.get("service"), "Other"),
                    usageType,
                    blankTo(row.get("unit"), ""),
                    parse(row.get("quantity")),
                    parse(row.get("cost")),
                    // Region-less network charges exist — an edge location is not a region —
                    // and "Global" is the label the AWS invoice itself uses for them.
                    blankTo(row.get("region"), "Global"),
                    route.from(), route.to(), route.derived()));
        }
        return charges;
    }

    /**
     * The costliest flows inside each network category — one resource, one route, one price.
     *
     * <p>Grain is resource × service × region × usage type × route, which is the finest
     * thing a CUR can describe about a transfer. Usage type is in the grain rather than
     * folded away because it is what distinguishes a gateway's hourly fee from its
     * processing, and because it is what the route falls back to when the report has no
     * location columns.
     *
     * <p>Quantities are split into gigabytes and hours rather than summed into one number.
     * A NAT gateway bills both, and "4,645" as a total of 3,925 GB and 720 hours is a
     * quantity of nothing.
     *
     * <p>The windows run after grouping and before the rank filter, so the category totals
     * describe every flow in the month rather than the forty returned. That is what lets the
     * caller say "top 40 of 512, covering 94% of this category" honestly — summing the
     * returned rows instead would understate the category by its whole tail.
     */
    private List<TransferFlow> transferFlows(CurSource source, CloudAccount account,
                                             LocalDate start, LocalDate end,
                                             String fromExpr, String toExpr) {
        String cost = CurSql.numericExpr(source, "line_item_unblended_cost");
        String qty = CurSql.numericExpr(source, "line_item_usage_amount");
        String category = networkCategoryExpr();
        String resourceId = "COALESCE(NULLIF(line_item_resource_id, ''), '')";

        // Absent unless the Name tag reaches this report — see CurTagColumns. MAX because
        // the name has to survive the grouping; a resource renamed mid-month has two, and
        // either is a better label than its id.
        String nameExpr = tagColumns.resourceNameExpr(source, account).orElse(null);
        String nameInner = nameExpr == null ? "" : " MAX(" + nameExpr + ") AS resource_name,";
        String nameOuter = nameExpr == null ? "" : " resource_name,";

        // The AZ is what makes a cross-AZ flow actionable: it names the zone the resource is
        // talking out of. MAX rather than grouped, so a resource billed in two zones under
        // the same usage type stays one row instead of splitting into two half-rows.
        boolean hasAz = columnCatalog.has(source, account, "line_item_availability_zone");
        String azInner = hasAz
                ? " MAX(NULLIF(line_item_availability_zone, '')) AS availability_zone," : "";
        String azOuter = hasAz ? " availability_zone," : "";

        String routeSelect = (fromExpr == null ? "" : " " + fromExpr + " AS from_location,")
                + (toExpr == null ? "" : " " + toExpr + " AS to_location,");
        String routeOuter = (fromExpr == null ? "" : " from_location,")
                + (toExpr == null ? "" : " to_location,");
        String routeGroup = (fromExpr == null ? "" : ", " + fromExpr)
                + (toExpr == null ? "" : ", " + toExpr);

        String sql = "SELECT category, resource_id," + nameOuter + " service, region," + azOuter
                + routeOuter + " usage_type, gigabytes, hours, cost,"
                + " category_cost, category_flows"
                + " FROM ("
                + "SELECT " + category + " AS category,"
                + " " + resourceId + " AS resource_id,"
                + nameInner
                + " product_product_name AS service,"
                + " product_region AS region,"
                + azInner
                + routeSelect
                + " line_item_usage_type AS usage_type,"
                + " SUM(CASE WHEN upper(pricing_unit) = 'GB' THEN " + qty + " ELSE 0 END) AS gigabytes,"
                + " SUM(CASE WHEN upper(pricing_unit) IN ('HRS', 'HOURS', 'HOUR')"
                + "   THEN " + qty + " ELSE 0 END) AS hours,"
                + " SUM(" + cost + ") AS cost,"
                + " SUM(SUM(" + cost + ")) OVER (PARTITION BY " + category + ") AS category_cost,"
                // Counted against the same threshold the outer WHERE applies, not COUNT(*).
                // Counting every group made the footer contradict itself: a category with one
                // sub-cent flow reported "top 9 of 10 — 100% of this category", because the
                // tenth was excluded from the rows and from the money but still counted.
                + " SUM(CASE WHEN SUM(" + cost + ") > " + TRANSFER_MIN_COST + " THEN 1 ELSE 0 END)"
                + "   OVER (PARTITION BY " + category + ") AS category_flows,"
                + " ROW_NUMBER() OVER (PARTITION BY " + category
                + "   ORDER BY SUM(" + cost + ") DESC) AS rank_in_category"
                + " FROM " + source.qualifiedTable()
                + " WHERE " + accountPredicate(account)
                + " AND " + CurSql.partitionPredicate(source, start, end)
                + " AND " + CurSql.usageDatePredicate(source, start, end)
                + " AND " + NETWORK_USAGE_PREDICATE
                + " GROUP BY " + category + ", " + resourceId
                + ", product_product_name, product_region, line_item_usage_type" + routeGroup
                + ")"
                + " WHERE rank_in_category <= " + TRANSFER_FLOWS_PER_CATEGORY
                + " AND cost > " + TRANSFER_MIN_COST
                + " ORDER BY category_cost DESC, cost DESC";

        List<TransferFlow> flows = new ArrayList<>();
        for (Map<String, String> row : athena.query(source, account, sql)) {
            String usageType = blankTo(row.get("usage_type"), "");
            Route route = resolveRoute(usageType,
                    blankTo(row.get("from_location"), ""), blankTo(row.get("to_location"), ""));
            flows.add(new TransferFlow(
                    blankTo(row.get("category"), "Other network"),
                    blankTo(row.get("resource_id"), ""),
                    blankTo(row.get("resource_name"), ""),
                    blankTo(row.get("service"), "Other"),
                    blankTo(row.get("region"), "Global"),
                    blankTo(row.get("availability_zone"), ""),
                    route.from(), route.to(), route.derived(),
                    usageType,
                    parse(row.get("gigabytes")),
                    parse(row.get("hours")),
                    parse(row.get("cost")),
                    parse(row.get("category_cost")),
                    (long) parse(row.get("category_flows"))));
        }
        return flows;
    }

    /**
     * The route for one row: what the report said, or what the usage type implies.
     *
     * <p>The report's own columns always win. They are only overridden when blank, and only
     * then is the result marked derived — a reader deciding whether to act on "Mumbai →
     * N. Virginia" is entitled to know whether AWS said it or XamOps worked it out from a
     * usage type, and marking everything derived would devalue the rows that are not.
     */
    private Route resolveRoute(String usageType, String reportedFrom, String reportedTo) {
        if (!reportedFrom.isBlank() && !reportedTo.isBlank()) {
            return new Route(location(reportedFrom), location(reportedTo), false);
        }
        CurNetworkUsageType.Route derived = CurNetworkUsageType.parse(usageType);
        if (!derived.isKnown()) {
            // Nothing better to offer, so whatever the report gave stands, blank or not.
            return new Route(location(reportedFrom), location(reportedTo), false);
        }
        String from = reportedFrom.isBlank() ? derived.from() : location(reportedFrom);
        String to = reportedTo.isBlank() ? derived.to() : location(reportedTo);
        return new Route(from, to, true);
    }

    /**
     * One spelling per place, whoever named it.
     *
     * <p>AWS writes {@code "External"} in {@code product_to_location} for traffic leaving for
     * the internet, where {@link CurNetworkUsageType} calls the same thing "Internet". Left
     * alone, the identical route arrives under two labels depending on whether the report
     * happened to fill the column in — so a category carrying both would split one route
     * across two rows and halve each, which reads as two smaller problems instead of one real
     * one. "Internet" wins because it is the one a reader does not have to translate.
     */
    private String location(String reported) {
        return "External".equalsIgnoreCase(reported) ? CurNetworkUsageType.INTERNET : reported;
    }

    /** One end-to-end route, and whether it was inferred rather than reported. */
    private record Route(String from, String to, boolean derived) {
    }

    /**
     * Buckets a usage type into the category that explains the charge.
     *
     * <p>Ordered: the first match wins, so the more specific patterns come first. A NAT
     * gateway's bytes also match the generic transfer patterns below it, and moving that
     * arm down would file the largest network cost on most accounts under "egress".
     *
     * <p>The strings are a contract with the UI, which keys its icons and its explanation
     * of each category off them.
     */
    private String networkCategoryExpr() {
        return "CASE"
                + " WHEN line_item_usage_type LIKE '%NatGateway%' THEN 'NAT Gateway'"
                // Before the generic arms, and worth its own category rather than being
                // buried in "Other network": a VPC endpoint is the fix for NAT gateway
                // processing charges, so its cost is the number the NAT row is compared
                // against. Interface endpoints are billed as PrivateLink on some services.
                + " WHEN line_item_usage_type LIKE '%VpcEndpoint%'"
                + "   OR line_item_usage_type LIKE '%PrivateLink%' THEN 'VPC endpoint'"
                + " WHEN line_item_usage_type LIKE '%TransitGateway%' THEN 'Transit Gateway'"
                + " WHEN line_item_usage_type LIKE '%Regional-Bytes%'"
                + "   OR line_item_usage_type LIKE '%-AZ-%' THEN 'Cross-AZ'"
                + " WHEN line_item_usage_type LIKE '%AWS-Out%'"
                + "   OR line_item_usage_type LIKE '%AWS-In%' THEN 'Inter-region'"
                + " WHEN line_item_usage_type LIKE '%DataTransfer-Out%'"
                + "   OR line_item_usage_type LIKE '%-Out-Bytes%' THEN 'Internet egress'"
                + " WHEN line_item_usage_type LIKE '%DataTransfer-In%'"
                + "   OR line_item_usage_type LIKE '%-In-Bytes%' THEN 'Internet ingress'"
                + " ELSE 'Other network' END";
    }

    /**
     * Where traffic came from or went to, or null when this report cannot say.
     *
     * <p>Two columns can answer it and they differ in readability, not in meaning:
     * {@code product_from_location} holds "Asia Pacific (Mumbai)", while
     * {@code product_from_region_code} holds "ap-south-1". The readable one is preferred and
     * the code is the fallback, because a route is read as a sentence.
     *
     * @param direction {@code from} or {@code to}
     */
    private String locationExpr(CurSource source, CloudAccount account, String direction) {
        List<String> present = columnCatalog.present(source, account,
                "product_" + direction + "_location",
                "product_" + direction + "_region_code");
        if (present.isEmpty()) {
            return null;
        }
        StringBuilder coalesce = new StringBuilder("COALESCE(");
        for (String column : present) {
            coalesce.append("NULLIF(").append(column).append(", ''), ");
        }
        return coalesce.append("'')").toString();
    }

    // ── Charge type breakdown ───────────────────────────────────────────────────

    /**
     * Spend split by line item type — usage vs tax vs credits vs refunds vs fees.
     *
     * <p>The usual answer to "why doesn't the dashboard match my invoice": the gap is
     * almost always credits or tax, and until now nothing in the product could show
     * that.
     */
    public List<ChargeTypeAmount> getChargeTypeBreakdown(CurSource source, CloudAccount account,
                                                         LocalDate start, LocalDate end) {
        String sql = "SELECT line_item_line_item_type AS charge_type,"
                + " SUM(" + CurSql.numericExpr(source, "line_item_unblended_cost") + ") AS cost"
                + " FROM " + source.qualifiedTable()
                + " WHERE " + accountPredicate(account)
                + " AND " + CurSql.partitionPredicate(source, start, end)
                + " AND " + CurSql.usageDatePredicate(source, start, end)
                + " GROUP BY line_item_line_item_type"
                + " ORDER BY cost DESC";

        List<ChargeTypeAmount> result = new ArrayList<>();
        for (Map<String, String> row : athena.query(source, account, sql)) {
            String type = row.get("charge_type");
            result.add(new ChargeTypeAmount(type == null || type.isBlank() ? "Unknown" : type,
                    parse(row.get("cost"))));
        }
        return result;
    }

    // ── Unused commitment ───────────────────────────────────────────────────────

    /**
     * Money spent on reservations and Savings Plans that went unused.
     *
     * <p>A concrete, actionable number the coverage percentages on the RI/SP page
     * cannot give: "you wasted $X this month" rather than "you are 72% covered".
     */
    public UnusedCommitment getUnusedCommitment(CurSource source, CloudAccount account,
                                                LocalDate start, LocalDate end) {
        String unusedRecurring = CurSql.numericExpr(source, "reservation_unused_recurring_fee");
        String unusedAmortized =
                CurSql.numericExpr(source, "reservation_unused_amortized_upfront_fee_for_billing_period");

        String sql = "SELECT SUM(" + unusedRecurring + ") AS unused_recurring,"
                + " SUM(" + unusedAmortized + ") AS unused_upfront"
                + " FROM " + source.qualifiedTable()
                + " WHERE " + accountPredicate(account)
                + " AND " + CurSql.partitionPredicate(source, start, end)
                + " AND " + CurSql.usageDatePredicate(source, start, end)
                + " AND line_item_line_item_type = 'RIFee'";

        List<Map<String, String>> rows = athena.query(source, account, sql);
        if (rows.isEmpty()) {
            return new UnusedCommitment(0, 0, 0);
        }
        double recurring = parse(rows.get(0).get("unused_recurring"));
        double upfront = parse(rows.get(0).get("unused_upfront"));
        return new UnusedCommitment(recurring, upfront, recurring + upfront);
    }

    // ── Container cost allocation ───────────────────────────────────────────────

    /**
     * Per-namespace EKS cost, from split cost allocation data.
     *
     * <p>Requires the report to carry {@code splitLineItem/*}, which only exists when
     * it was created with SPLIT_COST_ALLOCATION_DATA — check
     * {@link CurSource#getSplitCostDataEnabled()} before calling, or expect an empty
     * list.
     */
    public List<ContainerCost> getContainerCosts(CurSource source, CloudAccount account,
                                                 LocalDate start, LocalDate end) {
        if (!Boolean.TRUE.equals(source.getSplitCostDataEnabled())) {
            logger.debug("[CUR] Source {} has no split cost allocation data", source.getId());
            return List.of();
        }

        String splitCost = CurSql.numericExpr(source, "split_line_item_split_cost");
        String unusedCost = CurSql.numericExpr(source, "split_line_item_unused_cost");

        String sql = "SELECT resource_tags_aws_eks_namespace AS namespace,"
                + " split_line_item_parent_resource_id AS parent,"
                + " SUM(" + splitCost + ") AS split_cost,"
                + " SUM(" + unusedCost + ") AS unused_cost"
                + " FROM " + source.qualifiedTable()
                + " WHERE " + accountPredicate(account)
                + " AND " + CurSql.partitionPredicate(source, start, end)
                + " AND " + CurSql.usageDatePredicate(source, start, end)
                + " AND split_line_item_parent_resource_id IS NOT NULL"
                + " GROUP BY resource_tags_aws_eks_namespace, split_line_item_parent_resource_id"
                + " ORDER BY split_cost DESC";

        List<ContainerCost> result = new ArrayList<>();
        for (Map<String, String> row : athena.query(source, account, sql)) {
            String namespace = row.get("namespace");
            result.add(new ContainerCost(
                    namespace == null || namespace.isBlank() ? "unallocated" : namespace,
                    row.get("parent"),
                    parse(row.get("split_cost")),
                    parse(row.get("unused_cost"))));
        }
        return result;
    }

    // ── Shared helpers ──────────────────────────────────────────────────────────

    /**
     * The tenant boundary, duplicated from {@link CurCostDataSource} rather than
     * shared, so that neither class can have it removed by a change to the other.
     */
    private String accountPredicate(CloudAccount account) {
        String accountId = account.getAwsAccountId();
        if (accountId == null || !accountId.matches("\\d{12}")) {
            throw new IllegalArgumentException(
                    "Refusing to query CUR without a valid 12-digit account id (got: " + accountId + ")");
        }
        return "line_item_usage_account_id = '" + accountId + "'";
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

    // ── Results ─────────────────────────────────────────────────────────────────

    /** Amortized spend against public on-demand cost, and the resulting rate (0–1). */
    public record SavingsRate(double amortizedCost, double publicOnDemandCost,
                              double savings, double rate) {
    }

    /** One service's cost and usage change, decomposed into price and volume effects. */
    public record PriceVolumeChange(String service, double priorCost, double currentCost,
                                    double costChange, double priorQuantity, double currentQuantity,
                                    double priceEffect, double volumeEffect) {
    }

    /** One billed line as the invoice shows it. */
    public record ServiceCharge(String service, String region, String description,
                                String usageType, String unit, double quantity, double cost) {
    }

    /**
     * One resource's cost for the period.
     *
     * <p>An empty {@code resourceId} marks a charge that belongs to no resource — tax,
     * support, subscriptions — which the caller groups separately rather than hiding.
     *
     * @param name the resource's {@code Name} tag, or empty when the report does not carry
     *             it. Empty is the normal case for an account that never activated the tag
     *             for cost allocation, so callers fall back to the id rather than treating
     *             it as missing data.
     */
    public record ResourceCost(String resourceId, String name, String service, String region,
                               String instanceType, double cost) {
    }

    /**
     * A page of resources plus the totals needed to say what it leaves out.
     *
     * @param totalCost  every resource in the period, not just the returned ones
     * @param groupCount how many there were in total
     */
    public record TopResources(List<ResourceCost> resources, double totalCost, long groupCount) {
    }

    /**
     * One resource's month-over-month movement, its per-line-item detail, and its daily series.
     *
     * <p>What a drill-down on a single resource needs, in one payload. The resource table answers
     * "which resource costs the most"; this answers "and why did that change", which is the question a
     * reader has immediately afterwards and which no aggregate can reach.
     *
     * @param instanceType kept in step with {@link ResourceCost} so one drawer renders every provider —
     *                     the instance type on AWS, the resource group on Azure
     * @param comparisonAvailable false when the earlier month is not in the report at all, in which case
     *                            the prior figures and both effects are zero and mean nothing. A CUR
     *                            begins at the month it was created and never backfills, so this is the
     *                            normal state for a recently connected report — and the reason this is a
     *                            flag rather than an absent payload: the lines and the daily series are
     *                            still real, only the comparison is impossible.
     * @param lines        per usage type, largest movement first. This is the real depth: it separates
     *                     "the volume grew" from "the volume's rate changed" inside one resource.
     * @param daily        the selected month's spend, day by day, so the shape of the change is visible
     */
    public record ResourceChange(String resourceId, String name, String service, String region,
                                 String instanceType,
                                 double priorCost, double currentCost, double costChange,
                                 double priorQuantity, double currentQuantity,
                                 double priceEffect, double volumeEffect,
                                 boolean comparisonAvailable,
                                 List<ResourceLineChange> lines,
                                 List<DailyCost> daily) {
    }

    /** One usage type's movement inside a resource. Field-identical across all three clouds. */
    public record ResourceLineChange(String description, String usageType, String unit,
                                     double priorCost, double currentCost, double costChange,
                                     double priorQuantity, double currentQuantity,
                                     double priceEffect, double volumeEffect) {
    }

    /** One day's cost. {@code date} is ISO-8601, so it sorts as a string and parses without a format. */
    public record DailyCost(String date, double cost) {
    }

    /**
     * How much of the bill can be attributed to an owner.
     *
     * @param checkedTags       the required tags this report could actually be asked about
     * @param unavailableTags   required tags absent from the report — not activated for
     *                          cost allocation, so unknowable rather than missing
     * @param totalCost         resource spend in the period; excludes tax and other
     *                          account-level charges, which can carry no tag
     * @param allocatedCost     spend carrying every checked tag
     * @param byTag             per tag, the spend that carries it and the spend that does not
     * @param untaggedServices  unattributed spend by service, complete — each carries its
     *                          own total and count even though it lists only its worst few
     * @param untaggedCount     how many resources in total, which the folded lists cannot say
     * @param concentrationHead how many of the costliest {@code concentrationCost} covers
     * @param concentrationCost what those costliest few account for, so the caller can say
     *                          how much of the problem the top of the list actually is
     * @param availableTagKeys  every tag in the report, for the chargeback picker
     */
    public record TagCoverage(List<String> checkedTags, List<String> unavailableTags,
                              double totalCost, double allocatedCost,
                              List<TagKeyCoverage> byTag, List<UntaggedService> untaggedServices,
                              long untaggedCount, int concentrationHead, double concentrationCost,
                              List<String> availableTagKeys) {
    }

    /**
     * One service's unattributed spend.
     *
     * @param cost          every untagged resource in the service, not just the listed ones
     * @param resourceCount likewise
     * @param resources     the costliest few, for the drill-down
     */
    public record UntaggedService(String service, double cost, long resourceCount,
                                  List<UntaggedResource> resources) {
    }

    /** Accumulates one service's rows as they arrive, since they are interleaved by cost. */
    private record UntaggedServiceBuilder(String service, double cost, long resourceCount,
                                          List<UntaggedResource> resources) {

        UntaggedServiceBuilder(String service, double cost, long resourceCount) {
            this(service, cost, resourceCount, new ArrayList<>());
        }

        UntaggedService build() {
            return new UntaggedService(service, cost, resourceCount, List.copyOf(resources));
        }
    }

    /**
     * The SQL that decides whether a line item carries the required tags.
     *
     * @param allocated     spend carrying every one of them
     * @param perKeySelects one tagged/total pair per key, trailing comma included
     */
    private record TagPredicates(String allocated, String perKeySelects) {
    }

    /** One required tag's share of the bill. */
    public record TagKeyCoverage(String tagKey, double taggedCost, double untaggedCost) {
    }

    /**
     * One resource with spend that belongs to nobody.
     *
     * @param cost        the unattributed part of its spend, not its total
     * @param missingTags which of the checked tags it does not carry
     */
    public record UntaggedResource(String resourceId, String name, String service, String region,
                                   double cost, List<String> missingTags) {
    }

    /** One tag value's spend — a row of the chargeback report. */
    public record TagValueCost(String value, double cost, long resourceCount) {
    }

    /**
     * Network spend for a period, at the two grains that make it actionable.
     *
     * @param charges           every network charge at billing grain, with its route. Correct
     *                          even on a report with no resource ids, which is why it is not
     *                          derived from {@code flows}.
     * @param flows             the costliest resource-and-route combinations inside each
     *                          category. Empty when the report has no resource ids.
     * @param routeDataAvailable whether the report carries {@code product_from_location} and
     *                          {@code product_to_location}. False does not mean the routes are
     *                          missing — they are recovered from the usage type — it means
     *                          every route shown is derived rather than reported.
     * @param resourceDataAvailable whether the report carries {@code line_item_resource_id}.
     *                          False means the route fields are blank because the report
     *                          cannot say, not because the traffic had no route — a
     *                          distinction the UI has to draw, since the two are
     *                          indistinguishable from empty strings.
     */
    public record DataTransferBreakdown(List<TransferCharge> charges,
                                        List<TransferFlow> flows,
                                        boolean routeDataAvailable,
                                        boolean resourceDataAvailable) {
    }

    /**
     * One network charge, categorised by what drives it and routed where it went.
     *
     * @param region        the region billed for the charge, or "Global" when it has none
     * @param fromLocation  where the traffic originated — "Asia Pacific (Mumbai)",
     *                      "Internet", "CloudFront edge". Empty when neither the report nor
     *                      the usage type says.
     * @param toLocation    where it went, on the same terms
     * @param routeDerived  true when the route was read out of the usage type because the
     *                      report's own location columns were blank
     */
    public record TransferCharge(String category, String service, String usageType,
                                 String unit, double quantity, double cost,
                                 String region, String fromLocation, String toLocation,
                                 boolean routeDerived) {
    }

    /**
     * One resource's traffic on one route — the finest thing a CUR can describe.
     *
     * <p>Deliberately not "resource to resource". A line item names only the resource being
     * charged, so the far end of a flow is a location and never a peer id; that answer is in
     * VPC Flow Logs, not in any billing report.
     *
     * @param resourceId       the gateway, bucket, instance or load balancer being charged.
     *                         Empty for network spend the report attributes to no resource.
     * @param name             the resource's {@code Name} tag, or empty when the report does
     *                         not carry it — normal, not missing data, so the caller falls
     *                         back to the id
     * @param availabilityZone the zone the charge is billed in, which is what makes a
     *                         cross-AZ row actionable. Empty when the report has no AZ.
     * @param gigabytes        GB-denominated usage only
     * @param hours            hour-denominated usage only. Kept apart from {@code gigabytes}
     *                         because a gateway bills both and their sum is a quantity of
     *                         nothing.
     * @param categoryCost     every flow in this category, not only the listed ones, so the
     *                         caller can say what share the list covers
     * @param categoryFlows    how many there were in total, on the same terms
     */
    public record TransferFlow(String category, String resourceId, String name,
                               String service, String region, String availabilityZone,
                               String fromLocation, String toLocation, boolean routeDerived,
                               String usageType,
                               double gigabytes, double hours, double cost,
                               double categoryCost, long categoryFlows) {
    }

    /** Spend attributable to one line item type. */
    public record ChargeTypeAmount(String chargeType, double cost) {
    }

    /** Commitment spend that bought nothing. */
    public record UnusedCommitment(double unusedRecurringFee, double unusedUpfrontFee, double total) {
    }

    /** Cost attributed to one EKS namespace on one node. */
    public record ContainerCost(String namespace, String parentResourceId,
                                double splitCost, double unusedCost) {
    }
}
