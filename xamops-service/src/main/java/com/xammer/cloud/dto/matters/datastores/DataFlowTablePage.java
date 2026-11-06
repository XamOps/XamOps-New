package com.xammer.cloud.dto.matters.datastores;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * One page of Data Flow tables (design 5.4 items 14 and 15).
 *
 * @param searchCapped a search stopped after 100 vendor pages while more existed.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record DataFlowTablePage(List<Table> rows, int page, int pageSize, long total, int totalPages,
                                boolean searchCapped) {

    /** @param rowCountEstimate null stays null ("-"). */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Table(String id, String name, Long rowCountEstimate, Long foreignKeyCount, Long sensitiveCount) {
    }
}
