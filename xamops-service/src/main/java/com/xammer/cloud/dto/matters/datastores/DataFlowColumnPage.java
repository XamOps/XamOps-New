package com.xammer.cloud.dto.matters.datastores;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** One page of a Data Flow table's columns (design 5.4 item 16). */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record DataFlowColumnPage(List<Column> rows, int page, int pageSize, long total, int totalPages) {

    /** @param dataElements {@code data_elements[].name}, in API order. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Column(String id, String name, String nativeType, Long sensitiveCount, List<String> dataElements) {
    }
}
