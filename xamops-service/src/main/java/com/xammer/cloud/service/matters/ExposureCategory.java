package com.xammer.cloud.service.matters;

import java.util.Locale;
import java.util.Optional;

/**
 * The six exposure categories of the Data Lineage page, in the console's order (build 4 design 5.4).
 *
 * <p>The constant name is the vendor's category id and the page's {@code category} URL value. The titles
 * are the console's card titles, used as names while the switch is off or the vendor gives none.
 * {@code DATA_RESIDENCY} is not one of them: the console drops it.
 */
public enum ExposureCategory {
    SENSITIVE_FINDINGS("Sensitive Findings"),
    EXCESSIVE_EXPOSURE("Excessive Exposure"),
    HIGH_VOLUME_ACCESS("High Volume Access"),
    STALE_DATA("Stale Data"),
    RISKY_USER("Risky User"),
    PUBLIC_EXPOSURE("Public Exposure");

    private final String title;

    ExposureCategory(String title) {
        this.title = title;
    }

    /** The console's card title, such as "Sensitive Findings". */
    public String title() {
        return title;
    }

    /**
     * The constant whose name equals {@code raw} exactly (upper-case, no spaces).
     *
     * @throws DspmBadRequestException on {@code category} otherwise, absent included
     */
    public static ExposureCategory fromParam(String raw) {
        if (raw != null) {
            for (ExposureCategory category : values()) {
                if (category.name().equals(raw)) {
                    return category;
                }
            }
        }
        throw new DspmBadRequestException("category", "category must be one of the exposure categories");
    }

    /** The constant whose name equals the vendor id, ignoring case and surrounding spaces; empty otherwise. */
    public static Optional<ExposureCategory> fromVendorId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        String normalised = id.trim().toUpperCase(Locale.ROOT);
        for (ExposureCategory category : values()) {
            if (category.name().equals(normalised)) {
                return Optional.of(category);
            }
        }
        return Optional.empty();
    }
}
