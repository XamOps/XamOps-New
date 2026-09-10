package com.xammer.cloud.service.cur;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.CurSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /** What spend with no value for the tag is filed under. */
    private static final String UNASSIGNED = "(unassigned)";

    private final CurAthenaQueryService athena;
    private final CurTagColumns tagColumns;

    public CurAdvancedMetricsService(CurAthenaQueryService athena, CurTagColumns tagColumns) {
        this.athena = athena;
        this.tagColumns = tagColumns;
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

            double curRate = curQty > 0 ? curCost / curQty : 0;
            double priorRate = priorQty > 0 ? priorCost / priorQty : 0;

            // Decompose at the prior rate: volume effect is the quantity change valued at
            // the old unit price, and price effect is whatever is left.
            //
            // The baseline falls back to the current rate when there was no prior usage.
            // Without that, a service that first appeared this period has priorRate 0, so
            // the volume term vanishes and its entire cost is reported as a price
            // increase — the opposite of the truth, since a new service is pure volume.
            double baselineRate = priorQty > 0 ? priorRate : curRate;
            double volumeEffect = (curQty - priorQty) * baselineRate;

            // Price is the residual rather than (Δrate x qty) computed independently, so
            // the two effects always sum to the cost change exactly. Computing both
            // directly leaves a cross-term that shows up as an unexplained gap.
            double priceEffect = (curCost - priorCost) - volumeEffect;

            changes.add(new PriceVolumeChange(service, priorCost, curCost, curCost - priorCost,
                    priorQty, curQty, priceEffect, volumeEffect));
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
     */
    public List<TransferCharge> getDataTransferCosts(CurSource source, CloudAccount account,
                                                     LocalDate start, LocalDate end) {
        String cost = CurSql.numericExpr(source, "line_item_unblended_cost");
        String qty = CurSql.numericExpr(source, "line_item_usage_amount");

        // Ordered: the first match wins, so the more specific patterns come first. A NAT
        // gateway's bytes also match the generic transfer patterns below it.
        String category =
                "CASE"
                + " WHEN line_item_usage_type LIKE '%NatGateway%' THEN 'NAT Gateway'"
                + " WHEN line_item_usage_type LIKE '%Regional-Bytes%'"
                + "   OR line_item_usage_type LIKE '%-AZ-%' THEN 'Cross-AZ'"
                + " WHEN line_item_usage_type LIKE '%AWS-Out%'"
                + "   OR line_item_usage_type LIKE '%AWS-In%' THEN 'Inter-region'"
                + " WHEN line_item_usage_type LIKE '%DataTransfer-Out%'"
                + "   OR line_item_usage_type LIKE '%-Out-Bytes%' THEN 'Internet egress'"
                + " WHEN line_item_usage_type LIKE '%DataTransfer-In%'"
                + "   OR line_item_usage_type LIKE '%-In-Bytes%' THEN 'Internet ingress'"
                + " ELSE 'Other network' END";

        String sql = "SELECT " + category + " AS category,"
                + " product_product_name AS service,"
                + " line_item_usage_type AS usage_type,"
                + " pricing_unit AS unit,"
                + " SUM(" + qty + ") AS quantity,"
                + " SUM(" + cost + ") AS cost"
                + " FROM " + source.qualifiedTable()
                + " WHERE " + accountPredicate(account)
                + " AND " + CurSql.partitionPredicate(source, start, end)
                + " AND " + CurSql.usageDatePredicate(source, start, end)
                + " AND (line_item_usage_type LIKE '%DataTransfer%'"
                + "   OR line_item_usage_type LIKE '%NatGateway%'"
                + "   OR line_item_usage_type LIKE '%Bytes%')"
                + " GROUP BY " + category + ", product_product_name,"
                + " line_item_usage_type, pricing_unit"
                // Ingress is almost always free; a long tail of $0.00 rows would bury the
                // handful that cost real money.
                + " HAVING SUM(" + cost + ") > 0.001"
                + " ORDER BY cost DESC";

        List<TransferCharge> charges = new ArrayList<>();
        for (Map<String, String> row : athena.query(source, account, sql)) {
            charges.add(new TransferCharge(
                    blankTo(row.get("category"), "Other network"),
                    blankTo(row.get("service"), "Other"),
                    blankTo(row.get("usage_type"), ""),
                    blankTo(row.get("unit"), ""),
                    parse(row.get("quantity")),
                    parse(row.get("cost"))));
        }

        logger.info("✅ [CUR] Data transfer — {} rows for account {} ({} to {})",
                charges.size(), account.getAwsAccountId(), start, end);
        return charges;
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

    /** One network charge, categorised by what drives it. */
    public record TransferCharge(String category, String service, String usageType,
                                 String unit, double quantity, double cost) {
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
