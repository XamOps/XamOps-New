package com.xammer.cloud.dto.matters.datastores;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * A schema diagram for the Data Flow Graph View (design 5.4 items 15 and 16).
 *
 * @param nodesTruncated more than 1,000 tables arrived.
 * @param edgesTruncated more than 3,000 keys arrived.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record DataFlowErd(List<Node> nodes, List<Edge> edges, List<ExternalTable> externalTables,
                          boolean nodesTruncated, boolean edgesTruncated) {

    /**
     * @param id         opens the table panel and loads the expanded card's columns.
     * @param keyColumns the FK chips, in edge order.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Node(String id, String name, Long sensitiveCount, List<String> keyColumns) {
    }

    /**
     * @param id        an opaque React key.
     * @param targetKey a node id, or an {@link ExternalTable#key()}.
     * @param external  the target is outside the diagram: drawn dashed.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Edge(String id, String sourceTableId, String targetKey, boolean external) {
    }

    /** @param key {@code ext:<schema>.<table>}. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ExternalTable(String key, String schemaName, String tableName) {
    }
}
