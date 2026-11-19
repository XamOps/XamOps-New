package com.xammer.cloud.service.matters;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * The vendor's Data Lineage filter options, cache-only ({@code lnopts}) and never returned (build 4 design
 * 5.4). The service reads it for the category list, the More filters options, the membership check and
 * the Exclude complement.
 *
 * @param categoryIds    the known {@link ExposureCategory} names present, in enum order.
 * @param categoryNames  id → the vendor's name, else the enum title.
 * @param severities     upper-case bands, in vendor order.
 * @param datastoreNames in vendor order.
 * @param labels         in vendor order.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record LineageFilterSnapshot(List<String> categoryIds, Map<String, String> categoryNames,
                                    List<Option> severities, List<Option> datastoreNames, List<Option> labels) {

    /**
     * @param value    what the browser sends.
     * @param label    what the pane shows.
     * @param vendorId what Matters gets.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Option(String value, String label, String vendorId) {
    }

    /** The options of a filter dimension ({@code severity}, {@code datastoreName}, {@code label}). */
    public List<Option> options(String dimension) {
        return switch (dimension) {
            case LineageFilters.SEVERITY -> severities;
            case LineageFilters.DATASTORE_NAME -> datastoreNames;
            case LineageFilters.LABEL -> labels;
            default -> throw new IllegalArgumentException("unknown lineage filter dimension");
        };
    }
}
