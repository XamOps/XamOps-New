package com.xammer.cloud.dto.matters.datastores;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * A table's keys for the Graph View table panel's Relationships tab (design 5.4 item 18).
 *
 * @param outgoing in API order.
 * @param incoming in API order.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record DataFlowRelations(List<Relation> outgoing, List<Relation> incoming) {

    /** @param tableName the other table. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Relation(String schemaName, String tableName, List<ColumnPair> columns) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ColumnPair(String sourceColumn, String targetColumn) {
    }
}
