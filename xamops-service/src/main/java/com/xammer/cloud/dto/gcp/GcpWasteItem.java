package com.xammer.cloud.dto.gcp;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A single wasted GCP resource.
 *
 * <p>{@link #monthlySavings} is always denominated in {@link #currency}, which defaults
 * to USD. Detectors must emit USD; conversion to a display currency happens once at the
 * presentation boundary. Previously some detectors wrote USD and others wrote INR into
 * this field, and the summary endpoints added the two together.
 */
@Data
@NoArgsConstructor
public class GcpWasteItem {

    /** Currency code used for amounts produced inside the optimization services. */
    public static final String BASE_CURRENCY = "USD";

    /** Unique resource identifier used for delete operations (name, ID, or full resource path). */
    private String resourceId;

    /** Human-readable display name. */
    private String resourceName;

    /** Resource category, e.g. "Idle Persistent Disk", "Unused IP Address". */
    private String type;

    /** Region or zone, e.g. "us-central1", "europe-west1-b", "global". */
    private String location;

    /** Estimated monthly savings, denominated in {@link #currency}. */
    private double monthlySavings;

    /** Human-readable reason this resource is considered waste. */
    private String reason;

    /**
     * ISO-4217 currency code for {@link #monthlySavings}. Always {@value #BASE_CURRENCY}
     * as produced by the detectors; set explicitly so consumers never have to guess.
     */
    private String currency = BASE_CURRENCY;

    /**
     * True when the savings figure could not be determined and {@link #monthlySavings} is
     * therefore zero as a placeholder rather than as a real value. Consumers must exclude
     * these from savings totals so an unpriced item is not reported as free.
     */
    private boolean savingsUnknown;

    /**
     * Canonical constructor. {@code monthlySavings} must be in
     * {@value #BASE_CURRENCY}.
     */
    public GcpWasteItem(String resourceId, String resourceName, String type, String location,
                        double monthlySavings, String reason) {
        this.resourceId = resourceId;
        this.resourceName = resourceName;
        this.type = type;
        this.location = location;
        this.monthlySavings = monthlySavings;
        this.reason = reason;
        this.currency = BASE_CURRENCY;
        this.savingsUnknown = false;
    }

    /**
     * Creates an item whose cost impact is real but not yet quantified, so that it is
     * counted as a finding without contributing a misleading zero to savings totals.
     */
    public static GcpWasteItem withUnknownSavings(String resourceId, String resourceName, String type,
                                                  String location, String reason) {
        GcpWasteItem item = new GcpWasteItem(resourceId, resourceName, type, location, 0.0, reason);
        item.setSavingsUnknown(true);
        return item;
    }
}
