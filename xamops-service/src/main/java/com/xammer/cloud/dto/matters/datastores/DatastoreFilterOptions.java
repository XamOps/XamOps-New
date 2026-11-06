package com.xammer.cloud.dto.matters.datastores;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Filter options of the Datastores list (design 5.4 item 2). Also the cached facets, where
 * {@code integration_id} options still carry the raw id as their label.
 *
 * @param dimensions in vendor order, limited to the tab's allow-list.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record DatastoreFilterOptions(List<Dimension> dimensions) {

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Dimension(String key, List<Option> options, boolean hasMore) {
    }

    /** @param count null when the vendor sends none. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Option(String value, String label, Long count) {
    }
}
