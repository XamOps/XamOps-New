package com.xammer.cloud.dto.matters.datastores;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * One page of the Data Lineage public path's datastores (build 7 design 5.2): the project's endpoints with
 * files, then the drive people with lineage data.
 *
 * @param rows  never null
 * @param total the row count after the search, for the "›" rule
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PublicLineageDatastorePage(List<Datastore> rows, long total, int skip, int limit) {

    public PublicLineageDatastorePage {
        rows = rows == null ? List.of() : List.copyOf(rows);
    }

    /**
     * @param id      {@code PublicLineageKey.value()}; null when the node is not choosable
     * @param kind    {@code ENDPOINT} or {@code DRIVE}
     * @param service {@code ENDPOINT}, {@code GOOGLE_DRIVE}, {@code ONE_DRIVE}, {@code SHARE_POINT}, another
     *                provider code upper-cased, or null: the node's icon
     * @param files   the "N files" chip; null when the count could not be read
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Datastore(String id, String kind, String name, String service, Long files) {
    }
}
