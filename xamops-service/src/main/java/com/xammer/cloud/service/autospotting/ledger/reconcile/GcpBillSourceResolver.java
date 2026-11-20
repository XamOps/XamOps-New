package com.xammer.cloud.service.autospotting.ledger.reconcile;

import com.xammer.cloud.domain.BillingSource;
import com.xammer.cloud.domain.CloudAccount;

/**
 * Which bill a GCP project's savings are checked against (design-ledger.md §4.2, PD-06, GR-11). The source is the
 * read-side rule {@link CloudAccount#resolveBillingSource()} (a null column falls back to {@code cspManaged}), the same
 * rule the cost pages use. OWN_EXPORT and XAMMER_EXPORT read the export table. A project whose bill is in the reseller
 * dataset (XAMMER_CSP_DATASET) is {@code MEASURED_ONLY} with reason {@code CSP_DATASET_PENDING} until V-GCP-14 is
 * answered and its reader ships: measured, never invoiced.
 */
public final class GcpBillSourceResolver {
    public static final String CSP_DATASET_PENDING = "CSP_DATASET_PENDING";

    /** The resolved source and whether savings stay measured only, with the reason. */
    public record Resolved(BillingSource source, boolean measuredOnly, String reason) {
        public boolean readsExport() {
            return source == BillingSource.OWN_EXPORT || source == BillingSource.XAMMER_EXPORT;
        }
    }

    private GcpBillSourceResolver() {}

    public static Resolved resolve(CloudAccount account) {
        return of(account.resolveBillingSource());
    }

    /** The same rule from stored columns: {@code billing_source} when set, else {@code csp_managed}. */
    public static Resolved resolve(String billingSourceColumn, boolean cspManaged) {
        BillingSource s = billingSourceColumn == null || billingSourceColumn.isBlank()
                ? (cspManaged ? BillingSource.XAMMER_CSP_DATASET : BillingSource.OWN_EXPORT)
                : BillingSource.valueOf(billingSourceColumn.trim());
        return of(s);
    }

    private static Resolved of(BillingSource s) {
        return s == BillingSource.XAMMER_CSP_DATASET ? new Resolved(s, true, CSP_DATASET_PENDING)
                : new Resolved(s, false, null);
    }
}
