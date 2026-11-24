package com.xammer.cloud.dto.matters.datastores;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * One cached 100-row vendor page of Data Flow tables or columns. Cache-only, never returned.
 *
 * @param total      the vendor's {@code pagination.total}.
 * @param totalPages the vendor's {@code pagination.total_pages}.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record DataFlowVendorPage<T>(List<T> rows, long total, int totalPages) {
}
