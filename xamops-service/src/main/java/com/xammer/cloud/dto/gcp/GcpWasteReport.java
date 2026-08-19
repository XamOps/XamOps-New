package com.xammer.cloud.dto.gcp;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;

/**
 * Result of a GCP waste scan, with explicit provenance.
 *
 * <p>{@link #status} exists so an empty {@link #items} list is never ambiguous. Previously
 * a disabled Recommender API, a missing IAM role and a genuinely clean project all produced
 * the same response — an empty list with {@code partial=false}, which actively asserted the
 * result was complete.
 */
@Data
@NoArgsConstructor
public class GcpWasteReport {

    /** Outcome of a waste scan. */
    public enum Status {
        /** The scan completed and the items list is authoritative. */
        OK,
        /** Some detectors timed out; the items present are valid but incomplete. */
        PARTIAL,
        /** The scan could not run, e.g. the Recommender API is disabled or IAM is missing. */
        ERROR,
        /** The scan exceeded its time budget entirely and returned nothing. */
        TIMEOUT
    }

    private List<GcpWasteItem> items;

    /** True when a detector group timed out and results are incomplete. */
    private boolean partial;

    /** Outcome of the scan. Consumers must check this before reporting "no waste found". */
    private Status status = Status.OK;

    /** Human-readable explanation when {@link #status} is not {@link Status#OK}. */
    private String message;

    /** Currency for every {@code monthlySavings} value in {@link #items}. */
    private String currency = GcpWasteItem.BASE_CURRENCY;

    public GcpWasteReport(List<GcpWasteItem> items, boolean partial) {
        this.items = items;
        this.partial = partial;
        this.status = partial ? Status.PARTIAL : Status.OK;
        this.currency = GcpWasteItem.BASE_CURRENCY;
    }

    /** A report representing a scan that could not run. */
    public static GcpWasteReport error(String message) {
        GcpWasteReport report = new GcpWasteReport(Collections.emptyList(), false);
        report.setStatus(Status.ERROR);
        report.setMessage(message);
        return report;
    }

    /** A report representing a scan that exceeded its time budget. */
    public static GcpWasteReport timeout(String message) {
        GcpWasteReport report = new GcpWasteReport(Collections.emptyList(), true);
        report.setStatus(Status.TIMEOUT);
        report.setMessage(message);
        return report;
    }
}
