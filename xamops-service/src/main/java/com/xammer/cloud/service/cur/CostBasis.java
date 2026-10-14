package com.xammer.cloud.service.cur;

/**
 * Which cost a figure is computed from.
 *
 * <p>An enum rather than a boolean or a raw string. The parameter has exactly two legal values
 * today, a third ({@code blended}) is conceivable, and a boolean called {@code amortized} at four
 * call sites is the shape that produces
 * {@code getChargesByService(source, account, start, end, false, true)}.
 */
public enum CostBasis {

    /** What AWS charged on the day. Matches the invoice line for line. */
    UNBLENDED,

    /** Commitments spread across their term. Comparable month to month; not the invoice figure. */
    AMORTIZED;

    /**
     * Parses a query parameter.
     *
     * @param raw null or blank for the default
     * @return the basis the caller asked for, or {@link #UNBLENDED} when it asked for nothing
     * @throws IllegalArgumentException on anything else — deliberately, rather than defaulting.
     *         A typo'd {@code basis=amortised} silently answered on the unblended basis is a
     *         figure labelled as something it is not, which is the one outcome this parameter
     *         exists to prevent.
     */
    public static CostBasis from(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNBLENDED;
        }
        return switch (raw.trim().toLowerCase()) {
            case "unblended" -> UNBLENDED;
            case "amortized" -> AMORTIZED;
            default -> throw new IllegalArgumentException(
                    "Expected 'unblended' or 'amortized', got '" + raw + "'");
        };
    }

    /** The word the UI and the logs use. Lowercase, matching the query parameter. */
    public String wire() {
        return name().toLowerCase();
    }
}
