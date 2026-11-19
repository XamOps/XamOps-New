package com.xammer.cloud.dto.matters.datastores;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Step 3 of the Data Lineage page: one page of a datastore's affected files (build 4 design 5.4,
 * endpoint L5).
 *
 * @param available false while the exposure switch is off.
 * @param rows      in API order.
 * @param total     {@code total_files}: whether the row shows "›".
 * @param skip      echoes the request.
 * @param limit     echoes the request.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record LineageFilePage(boolean available, List<File> rows, long total, int skip, int limit) {

    /**
     * @param id         24 hex or a UUID, lower-cased: opens the lineage; null: the node is not choosable.
     * @param extension  the file icon.
     * @param actor      the "Actor: …" line.
     * @param modifiedAt epoch seconds: the node date.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record File(String id, String name, String extension, String actor, Long modifiedAt) {
    }

    /** The switch is off. */
    public static LineageFilePage unavailable(int skip, int limit) {
        return new LineageFilePage(false, List.of(), 0, skip, limit);
    }
}
