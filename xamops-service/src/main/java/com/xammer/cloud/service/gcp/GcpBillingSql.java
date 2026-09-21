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
}
