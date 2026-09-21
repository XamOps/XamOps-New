package com.xammer.billops.service.cur;

/**
 * Why the Cost and Usage Report cannot answer for a given account and month.
 *
 * <p>An invoice built from the wrong source is worse than an invoice not built, so
 * the CUR path never degrades to Cost Explorer on its own. It stops and says which
 * of these it hit, and an admin decides whether to fall back. The codes are part of
 * the API the invoices page reads, so they are named for the cause rather than for
 * what the UI happens to say today.
 */
public enum CurUnavailable {

    /** {@code billops.cur.enabled} is off for this deployment. */
    CUR_DISABLED("Reading from the Cost and Usage Report is switched off for this environment."),

    /**
     * No report registered for the account. Normal for an account onboarded before
     * CUR support, or one that chose not to create a report.
     */
    NO_CUR_SOURCE("This account has no Cost and Usage Report connected, so there is nothing to read."),

    /** Registered, but xamops-service has not validated it — or validation failed. */
    SOURCE_NOT_ACTIVE("The connected Cost and Usage Report is not active."),

    /**
     * The report begins after the month asked for. A CUR does not backfill, so this
     * month will never appear in it however long anyone waits.
     */
    PERIOD_BEFORE_REPORT_START("The report does not go back that far, and a report never backfills."),

    /** A month that has not started. Nothing to read, from any source. */
    PERIOD_IN_FUTURE("That billing period has not started yet."),

    /**
     * The report is healthy but holds nothing for this month — most often a month
     * that closed before the report existed, or the first day or two of a new report
     * before AWS has delivered anything.
     */
    NO_DATA_FOR_MONTH("The report has no data for that month yet. AWS delivers the first report within 24 hours."),

    /** Athena refused or timed out. Transient far more often than not. */
    QUERY_FAILED("Reading the report failed."),

    /**
     * The stored aggregate for this month predates marketplace measurement, so
     * marketplace charges would be billed as zero rather than as what they are.
     */
    MARKETPLACE_UNMEASURED("The stored report data for that month does not record AWS Marketplace charges."),

    /**
     * CloudFront is resold at private rates, so its lines are rebuilt from usage.
     * With no usage from the report there is no basis to reprice on, and passing
     * AWS's own cost through would bill the customer at the wrong rate.
     */
    CLOUDFRONT_USAGE_MISSING("The report has no CloudFront usage for that month, so it cannot be repriced at contract rates.");

    private final String message;

    CurUnavailable(String message) {
        this.message = message;
    }

    /** Plain-language explanation, shown to the admin as-is. */
    public String message() {
        return message;
    }
}
