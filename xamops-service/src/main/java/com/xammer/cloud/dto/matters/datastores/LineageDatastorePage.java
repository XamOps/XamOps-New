package com.xammer.cloud.dto.matters.datastores;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Step 2 of the Data Lineage page: one page of a policy's affected datastores (build 4 design 5.4,
 * endpoint L4).
 *
 * @param available false while the exposure switch is off.
 * @param rows      in API order.
 * @param total     the pager.
 * @param skip      echoes the request.
 * @param limit     echoes the request.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record LineageDatastorePage(boolean available, List<Datastore> rows, long total, int skip, int limit) {

    /**
     * @param id      the exposure id: opens the files; null when it is not of the accepted format.
     * @param service {@code datastore_service} upper-cased: the node icon.
     * @param files   {@code affected_files_count}: the "N files" line.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Datastore(String id, String name, String service, Long files) {
    }

    /** The switch is off. */
    public static LineageDatastorePage unavailable(int skip, int limit) {
        return new LineageDatastorePage(false, List.of(), 0, skip, limit);
    }

    /** No row can match, such as an Exclude of every option; no vendor call was made. */
    public static LineageDatastorePage empty(int skip, int limit) {
        return new LineageDatastorePage(true, List.of(), 0, skip, limit);
    }
}
