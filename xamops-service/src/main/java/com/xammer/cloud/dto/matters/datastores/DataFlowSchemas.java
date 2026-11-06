package com.xammer.cloud.dto.matters.datastores;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * The Data Flow tree's schemas of one database (design 5.4 item 13).
 *
 * @param truncated more vendor pages remained after the 10-page bound.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record DataFlowSchemas(List<Schema> items, boolean truncated) {

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Schema(String id, String name) {
    }
}
