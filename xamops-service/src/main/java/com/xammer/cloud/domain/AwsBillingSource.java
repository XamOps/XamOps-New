package com.xammer.cloud.domain;

/**
 * Where an AWS account's cost data is read from.
 *
 * <p>Four states rather than a boolean, deliberately. The GCP side shipped
 * {@code csp_managed} as a boolean and had to be migrated to
 * {@link BillingSource} once a Xammer-billed project with its own export was
 * routed to the reseller dataset, found no rows, and reported zero spend on
 * every cost widget. The same failure is available here — an account pointed at
 * a CUR that does not contain it reads zero, and zero is indistinguishable from
 * "spent nothing" on a dashboard. So the "no CUR is possible" case gets its own
 * value instead of being the absence of a flag.
 */
public enum AwsBillingSource {

    /**
     * Account sits under a XamOps-owned management/payer account. Its costs are
     * rows in the MPA's own CUR, which XamOps reads from its own S3 bucket with
     * its own credentials — no customer-side stack, bucket, or role needed for
     * cost data.
     */
    XAMMER_MPA_CUR,

    /**
     * Customer's own account (standalone, or a member account whose payer we do
     * not manage) with a CUR that XamOps' onboarding stack created in it. Read
     * cross-account by assuming the account's XamOps role.
     */
    OWN_CUR,

    /**
     * Customer already had a CUR before onboarding and pointed XamOps at it.
     * Identical read path to {@link #OWN_CUR}; kept distinct because XamOps does
     * not own the report definition and must not assume its granularity, format,
     * or that resource IDs are enabled — see {@link CurSource} discovery fields.
     */
    EXISTING_CUR,

    /**
     * No CUR is obtainable for this account, and none ever will be without a
     * change on the customer's side. The usual cause: a member account whose
     * payer never enabled linked-account access to billing data, so the account
     * cannot create a CUR of its own and we have no access to the payer's.
     *
     * <p>This is a terminal state, not a pending one. It exists so the read path
     * can route to Cost Explorer permanently and the UI can say why, rather than
     * querying a CUR source that resolves to nothing and rendering $0.
     */
    COST_EXPLORER_ONLY;

    /** True when cost data comes from a CUR in a XamOps-owned bucket. */
    public boolean usesXamopsOwnedCur() {
        return this == XAMMER_MPA_CUR;
    }

    /** True when cost data comes from a CUR in the customer's own account. */
    public boolean usesCustomerOwnedCur() {
        return this == OWN_CUR || this == EXISTING_CUR;
    }

    /** True when a CUR should be queried at all, wherever it lives. */
    public boolean usesCur() {
        return this != COST_EXPLORER_ONLY;
    }
}
