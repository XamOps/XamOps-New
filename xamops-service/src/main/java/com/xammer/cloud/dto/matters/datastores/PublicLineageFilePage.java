package com.xammer.cloud.dto.matters.datastores;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * One page of a public-path datastore's files (build 7 design 5.2).
 *
 * @param rows never null
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PublicLineageFilePage(List<File> rows, long total, int skip, int limit) {

    public PublicLineageFilePage {
        rows = rows == null ? List.of() : List.copyOf(rows);
    }

    /**
     * @param id        endpoint: the lower-cased UUID; drive: the doc id as returned; null: not choosable
     * @param extension the icon
     * @param actor     endpoint: {@code currentUser}; drive: null (no field)
     * @param dateAt    epoch seconds; endpoint: {@code fileModifiedTime}; drive: {@code last_seen}
     * @param createdAt epoch seconds; endpoint: {@code fileCreatedTime}, the GAP-63 "File Created"; drive: null
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record File(String id, String name, String extension, String actor, Long dateAt, Long createdAt) {
    }
}
