package com.xammer.cloud.dto.matters.datastores;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * The SaaS drawer's Information tab (design 5.4 item 4).
 *
 * @param fileTypes by count descending, then label, then extension.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record SaasScanSummary(Part supported, Part unsupported, List<FileType> fileTypes, Properties properties) {

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Part(long count, long sizeBytes) {
    }

    /** @param extension the {@code file_type_breakdown} key: the chip's React key. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record FileType(String extension, String label, long count) {
    }

    /**
     * @param createdOn epoch seconds.
     * @param lastScan  epoch seconds.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Properties(String owner, Long datastoreSizeBytes, Long createdOn, Long lastScan) {
    }
}
