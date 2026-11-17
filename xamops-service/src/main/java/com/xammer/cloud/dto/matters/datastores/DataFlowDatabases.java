package com.xammer.cloud.dto.matters.datastores;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * The Data Flow tree's databases (design 5.4 item 12, rules in 5.6.10).
 *
 * @param truncated more vendor pages remained after the 10-page bound.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record DataFlowDatabases(List<Database> items, boolean truncated) {

    /** @param schemaless MYSQL or MARIADB: tables hang directly off the database. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Database(String id, String name, String engine, boolean schemaless) {
    }
}
