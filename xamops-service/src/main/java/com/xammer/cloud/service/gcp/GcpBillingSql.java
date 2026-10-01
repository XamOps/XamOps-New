package com.xammer.cloud.service.gcp;

/**
 * Shared SQL fragments for querying the GCP standard/detailed billing export.
 *
 * <p>Exists so there is exactly one definition of "what a cost is". Every cost query in the
 * GCP path previously computed {@code SUM(cost)}, which is the figure <em>before</em> credits.
 * The {@code credits} column of the billing export carries sustained-use discounts, committed
 * use discount credits, promotional credits and free-tier as <em>negative</em> amounts, and
 * Google's own console nets them. Reporting the gross figure meant every number XamOps showed
 * for a GCP project was higher than the customer's actual bill — silently, and by however much
 * discount they had earned.
 *
 * <p>Sixteen separate query strings had their own copy of that expression. Centralising it is
 * the point: a money definition that lives in sixteen places is one that will be fixed in
 * fifteen.
 *
 * @see <a href="https://cloud.google.com/billing/docs/how-to/bq-examples">Example queries for
 *      Cloud Billing data export</a>
 */
public final class GcpBillingSql {

    private GcpBillingSql() {
    }

    /**
     * Net cost over the grouped rows — the invoice figure.
     *
     * <p>A drop-in replacement for {@code SUM(cost)}. Three details are deliberate:
     *
     * <p><b>The credits total is a correlated subquery, not a join.</b> {@code credits} is a
     * REPEATED field, so {@code LEFT JOIN UNNEST(credits)} multiplies the row once per credit
     * and the accompanying {@code SUM(cost)} silently counts that row's cost several times.
     * Computing the credit total per row inside a scalar subquery cannot fan out. This is the
     * form Google documents for the invoice total, and the join form is a known way to get a
     * plausible-looking wrong number.
     *
     * <p><b>{@code IFNULL(..., 0)}</b> because a row with no credits yields NULL from the
     * subquery, and {@code cost + NULL} is NULL — which would erase, not preserve, that row's
     * cost.
     *
     * <p><b>{@code CAST(... AS NUMERIC)}</b> because the export stores cost as FLOAT64.
     * Google's own documentation notes the float form accumulates rounding error over the
     * aggregate, and their example shows it: {@code $1005.004832999999984} against an actual
     * {@code $1005.00}. NUMERIC is exact decimal, so summing is exact and the single
     * conversion to a double happens once, at the Java boundary, rather than compounding
     * across every row. On a product that renders these figures as currency and feeds an
     * invoicing module, that difference is the whole point.
     */
    public static final String NET_COST_SUM =
            "(SUM(CAST(cost AS NUMERIC)) + SUM(IFNULL((SELECT SUM(CAST(c.amount AS NUMERIC)) "
                    + "FROM UNNEST(credits) AS c), 0)))";

    /**
     * What the same usage would have cost at list price, for the effective savings rate.
     *
     * <p>Falls back to {@code cost} when {@code cost_at_list} is null, which it is for rows that
     * have no list price to discount — tax, adjustments, some Marketplace items. Treating those
     * as "list equals net" is the honest reading: nothing was discounted, so they contribute
     * zero savings rather than inflating or deflating the rate.
     *
     * <p>Not usable on the reseller path, which already reports {@code cost_at_list} as the cost
     * itself — see the note in {@code GcpCostService} on the two cost bases. A savings rate there
     * would always be zero, which is a fact about the query and not about the customer.
     */
    public static final String LIST_COST_SUM = "SUM(CAST(IFNULL(cost_at_list, cost) AS NUMERIC))";

    /** What spend carrying no value for the requested label key is filed under. */
    public static final String UNLABELLED = "(unlabelled)";

    /**
     * The value of one label key on a row, or {@link #UNLABELLED} when the row does not carry it.
     *
     * <p>A scalar subquery, deliberately, and this is the whole point of the method. The obvious
     * form — {@code FROM table, UNNEST(labels) AS l WHERE l.key = @k} — reads like a filter and
     * behaves like an inner join: that comma is a CROSS JOIN, so a row whose {@code labels} array
     * is empty, or which carries labels but not this key, disappears from the result entirely.
     * Unlabelled spend then reads as zero rather than as unlabelled, which is the one thing a
     * cost-allocation view exists to show. A subquery evaluates per row and cannot drop one.
     *
     * <p>An empty string counts as absent. "Has the key, set to nothing" and "does not have the
     * key" are different facts about the label and the same fact about ownership, which is what
     * this expression is used to measure.
     *
     * @param parameterName the named query parameter holding the label key, without the {@code @}
     */
    public static String labelValueExpr(String parameterName) {
        return "COALESCE((SELECT NULLIF(l.value, '') FROM UNNEST(labels) AS l"
                + " WHERE l.key = @" + parameterName + " LIMIT 1), '" + UNLABELLED + "')";
    }

    /**
     * Usage falling inside the requested dates, bound to {@code @startDate} and {@code @endDate}.
     *
     * <p>Filters on usage date rather than {@code invoice.month} for the same reason
     * {@code CspBillingQueryService} switched: GCP bills usage sitting on a month boundary to the
     * previous invoice period, so an invoice-month filter silently drops the first days of a
     * month and makes a range query disagree with the month-to-date total.
     */
    public static final String USAGE_DATE_PREDICATE =
            "DATE(usage_start_time) >= @startDate AND DATE(usage_start_time) <= @endDate";

    /** Scopes a query to one project. Always present: it is the tenant boundary. */
    public static final String PROJECT_PREDICATE = "project.id = @projectId";
}
