package com.xammer.cloud.dto.gcp;

import com.xammer.cloud.domain.BillingSource;

/**
 * Why a GCP project's cost page looks the way it does.
 *
 * <p>Deliberately <em>not</em> a fourth {@link BillingSource} value. {@code BillingSource}
 * answers "where are this project's costs read from" — a configuration choice made at
 * onboarding. This answers "can that source be read right now" — runtime state. Folding the
 * second into the first would mean an account transitioning
 * {@code OWN_EXPORT -> NOT_CONFIGURED -> OWN_EXPORT}, losing the operator's intent in
 * between, and would leave {@link BillingSource#usesExportTable()} needing a third answer
 * meaning "neither, yet". Conflating two different questions in one column is what made a
 * Xammer-billed project read the reseller dataset and report zero spend, which is the bug
 * {@code BillingSource} was introduced to fix.
 *
 * <p>The summary wording lives here rather than in the UI for the same reason it does in
 * {@code PermissionGapService}: the customer's next action is a specific console or Account
 * Manager step, so the message has to name it. A state the customer cannot act on
 * ({@link State#CSP_DATASET_UNAVAILABLE}) says so instead of asking them to fix it.
 */
public class GcpBillingStatusDto {

    /**
     * Readiness of the configured cost source.
     *
     * <p>One value per <em>distinct customer action</em> — that is the entire point of the
     * type. Collapsing any two of these back together reproduces the blank page this
     * replaces, because the page would no longer know what to tell the customer to do.
     */
    public enum State {

        /**
         * The source is usable. Says nothing about whether a given date range has spend:
         * an empty cost list alongside {@code READY} means the project genuinely spent
         * nothing in that period, which is the distinction this type exists to make.
         */
        READY,

        /**
         * The project reads from an export table and no table could be found — not on the
         * account, not in configuration, and not discoverable in BigQuery. Billing export
         * cannot be enabled through an API, so this is always a manual console step.
         */
        EXPORT_NOT_CONFIGURED,

        /**
         * A table is recorded but unusable — malformed (not {@code project.dataset.table})
         * or it no longer exists in BigQuery. Distinct from
         * {@link #EXPORT_NOT_CONFIGURED} because the fix is correcting an existing value
         * rather than performing first-time setup.
         */
        EXPORT_TABLE_INVALID,

        /**
         * The table exists but has no rows yet. Export is not retroactive beyond the
         * previous month and takes up to 24h to first populate, so this is the normal
         * state immediately after onboarding, not a fault.
         */
        EXPORT_PENDING,

        /**
         * The project is flagged as reseller-billed but no CSP billing config resolves for
         * it. A Xammer-side misconfiguration — the customer cannot fix it, so the wording
         * does not ask them to.
         */
        CSP_DATASET_UNAVAILABLE,

        /** The service account exists but BigQuery refused the read. */
        ACCESS_DENIED,

        /** No usable service account key, so no BigQuery client could be built at all. */
        CREDENTIALS_MISSING,

        /** Something else failed. Carries the underlying message in {@code detail}. */
        UNKNOWN
    }

    private String projectId;
    private State state;
    private BillingSource billingSource;

    /** Fully-qualified export table backing this project, when one resolved. */
    private String exportTable;

    /**
     * Ready-to-render sentence, or null when {@link State#READY}. Null is meaningful: it
     * tells the page there is nothing to explain and an empty chart means zero spend.
     */
    private String summary;

    /** Underlying technical detail for logs and support, never the customer-facing string. */
    private String detail;

    /** True when the customer can resolve this themselves. */
    private boolean actionRequired;

    public GcpBillingStatusDto() {
    }

    private GcpBillingStatusDto(String projectId, State state, BillingSource billingSource,
            String exportTable, String summary, String detail, boolean actionRequired) {
        this.projectId = projectId;
        this.state = state;
        this.billingSource = billingSource;
        this.exportTable = exportTable;
        this.summary = summary;
        this.detail = detail;
        this.actionRequired = actionRequired;
    }

    public static GcpBillingStatusDto ready(String projectId, BillingSource source, String exportTable) {
        return new GcpBillingStatusDto(projectId, State.READY, source, exportTable, null, null, false);
    }

    public static GcpBillingStatusDto exportNotConfigured(String projectId, BillingSource source) {
        return new GcpBillingStatusDto(projectId, State.EXPORT_NOT_CONFIGURED, source, null,
                "No BigQuery billing export is configured for project " + projectId
                        + ", so there is no cost data to read. Enable Cloud Billing export to BigQuery on the "
                        + "billing account, then add the table in Account Manager. Export cannot be enabled "
                        + "through an API, so this has to be done in the Google Cloud console.",
                null, true);
    }

    public static GcpBillingStatusDto exportTableInvalid(String projectId, BillingSource source,
            String exportTable, String detail) {
        return new GcpBillingStatusDto(projectId, State.EXPORT_TABLE_INVALID, source, exportTable,
                "The billing export table recorded for project " + projectId + " cannot be read. "
                        + "Correct it in Account Manager — it must be the fully-qualified "
                        + "project.dataset.table name of the export, for example "
                        + "my-project.billing_export.gcp_billing_export_v1_XXXXXX_XXXXXX_XXXXXX.",
                detail, true);
    }

    public static GcpBillingStatusDto exportPending(String projectId, BillingSource source, String exportTable) {
        return new GcpBillingStatusDto(projectId, State.EXPORT_PENDING, source, exportTable,
                "Billing export for project " + projectId + " is configured but has not delivered any rows yet. "
                        + "Google can take up to 24 hours to populate a new export, and it does not backfill "
                        + "beyond the previous month. Cost data will appear on the next refresh once rows arrive.",
                null, false);
    }

    public static GcpBillingStatusDto cspDatasetUnavailable(String projectId, BillingSource source) {
        return new GcpBillingStatusDto(projectId, State.CSP_DATASET_UNAVAILABLE, source, null,
                "Project " + projectId + " is billed through Xammer but no reseller billing dataset is linked "
                        + "to it, so no cost data can be read. This is a Xammer-side configuration issue — "
                        + "contact support rather than changing anything in your Google Cloud account.",
                null, false);
    }

    public static GcpBillingStatusDto accessDenied(String projectId, BillingSource source,
            String exportTable, String detail) {
        return new GcpBillingStatusDto(projectId, State.ACCESS_DENIED, source, exportTable,
                "BigQuery denied access to the billing export for project " + projectId + ". Grant the XamOps "
                        + "service account the BigQuery Data Viewer and BigQuery Job User roles on the project "
                        + "holding the export dataset, and the data will appear on the next refresh.",
                detail, true);
    }

    public static GcpBillingStatusDto credentialsMissing(String projectId, BillingSource source) {
        return new GcpBillingStatusDto(projectId, State.CREDENTIALS_MISSING, source, null,
                "XamOps has no usable service account key for project " + projectId + ", so it cannot reach "
                        + "BigQuery at all. Re-upload the service account key for this account in Account Manager.",
                null, true);
    }

    public static GcpBillingStatusDto unknown(String projectId, BillingSource source, String detail) {
        return new GcpBillingStatusDto(projectId, State.UNKNOWN, source, null,
                "Cost data for project " + projectId + " could not be loaded. The billing export was reachable "
                        + "but the check did not complete, so this is being reported rather than shown as zero "
                        + "spend. Retry, and contact support if it persists.",
                detail, false);
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public State getState() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }

    public BillingSource getBillingSource() {
        return billingSource;
    }

    public void setBillingSource(BillingSource billingSource) {
        this.billingSource = billingSource;
    }

    public String getExportTable() {
        return exportTable;
    }

    public void setExportTable(String exportTable) {
        this.exportTable = exportTable;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public boolean isActionRequired() {
        return actionRequired;
    }

    public void setActionRequired(boolean actionRequired) {
        this.actionRequired = actionRequired;
    }
}
