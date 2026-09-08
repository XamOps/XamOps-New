package com.xammer.cloud.service.cur;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.domain.CurSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
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

    private final CurAthenaQueryService athena;

    public CurAdvancedMetricsService(CurAthenaQueryService athena) {
        this.athena = athena;
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
