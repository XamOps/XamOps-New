package com.xammer.cloud.dto.matters.datastores;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** One page of a Data Flow table's columns (design 5.4 item 16). */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record DataFlowColumnPage(List<Column> rows, int page, int pageSize, long total, int totalPages) {

    /**
     * @param dataElements {@code data_elements[].name}, in API order.
     * @param primaryKey   {@code is_primary_key}: the PK badge.
     * @param foreignKey   a non-empty {@code foreign_key_constraint_names}: the FK badge.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Column(String id, String name, String nativeType, Long sensitiveCount, List<String> dataElements,
                         boolean primaryKey, boolean foreignKey) {
    }
}
