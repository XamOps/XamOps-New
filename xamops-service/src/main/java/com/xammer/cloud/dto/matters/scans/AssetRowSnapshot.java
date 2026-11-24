package com.xammer.cloud.dto.matters.scans;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.xammer.cloud.service.matters.DatastoreRowMapper;

import java.util.List;

/**
 * One tab's asset rows, read page by page (design 4.2). Cache-only: stored as {@code dsrows}, never returned.
 *
 * @param vendorTotal the vendor's {@code pagination.total}
 * @param truncated   the collector stopped at its page cap before {@code vendorTotal} rows
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AssetRowSnapshot(List<DatastoreRowMapper.MappedRow> rows, long vendorTotal, boolean truncated) {
}
