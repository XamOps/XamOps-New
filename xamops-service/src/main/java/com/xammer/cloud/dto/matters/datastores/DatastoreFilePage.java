package com.xammer.cloud.dto.matters.datastores;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * One page of the Sensitive tab's File View (design 5.4 item 7). The rows are the {@code dsfiles} cache
 * entry ({@code List<FileRow>}). Samples are stripped.
 *
 * @param truncated more than 50,000 file rows existed and the rest were dropped.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record DatastoreFilePage(List<FileRow> rows, int page, int pageSize, long total, int totalPages,
                                boolean truncated) {

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record FileRow(String path, String name, long count, List<String> labels, List<FileEntity> entities) {
    }

    /** @param name {@code ""} for the blank entity. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record FileEntity(String name, String label) {
    }
}
