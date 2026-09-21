package com.xammer.billops.service;

/**
 * Shared SQL fragments for querying the GCP standard/detailed billing export.
 *
 * <p>A deliberate duplicate of {@code com.xammer.cloud.service.gcp.GcpBillingSql} in
 * xamops-service. BillOps is a separate Maven module with no dependency on xamops-service,
 * and the only shared module ({@code permissions-common}) is about IAM permissions, so there
 * is nowhere correct to put one copy. If a third consumer appears, promote this to a shared
 * billing module rather than adding a third copy.
 *
 * <p>Keeping them in sync matters more here than anywhere else: this module generates
 * customer invoices, so a figure that does not match Google's bill becomes a figure that does
 * not match what Xammer charges.
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
     * <p>A drop-in replacement for {@code SUM(cost)}, which is the figure <em>before</em>
     * credits. The export's {@code credits} field carries sustained-use discounts, committed
     * use discount credits, promotional credits and free tier as negative amounts, and
     * Google's console nets them. Summing cost alone overstates every total by whatever
     * discount the customer earned.
     *
     * <p>The credit total is a correlated subquery rather than a join because {@code credits}
     * is REPEATED: {@code LEFT JOIN UNNEST(credits)} duplicates the row per credit and makes
     * the accompanying cost sum count that row several times. {@code IFNULL} guards rows with
     * no credits, where the subquery yields NULL and {@code cost + NULL} would erase the cost.
     * The NUMERIC casts avoid the float rounding drift Google documents for the naive form.
     */
    public static final String NET_COST_SUM =
            "(SUM(CAST(cost AS NUMERIC)) + SUM(IFNULL((SELECT SUM(CAST(c.amount AS NUMERIC)) "
                    + "FROM UNNEST(credits) AS c), 0)))";
}
