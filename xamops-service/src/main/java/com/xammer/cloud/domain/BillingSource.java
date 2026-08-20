package com.xammer.cloud.domain;

/**
 * Where a GCP account's cost data is read from.
 *
 * <p>Separate from "who pays the bill": Xammer-billed projects come in two flavours, and
 * conflating them is what made a Xammer-billed project with its own BigQuery export read
 * the reseller dataset instead and report zero spend.
 */
public enum BillingSource {

    /** Customer manages their own billing export; costs come from {@code billingExportTable}. */
    OWN_EXPORT,

    /** Xammer-billed reseller project; costs come from the shared CSP billing dataset. */
    XAMMER_CSP_DATASET,

    /** Xammer-billed, but costs come from a standard BigQuery export in {@code billingExportTable}. */
    XAMMER_EXPORT;

    /** True when cost data should be read from the reseller dataset rather than an export table. */
    public boolean usesCspDataset() {
        return this == XAMMER_CSP_DATASET;
    }

    /** True when cost data should be read from {@code billingExportTable}. */
    public boolean usesExportTable() {
        return this == OWN_EXPORT || this == XAMMER_EXPORT;
    }
}
