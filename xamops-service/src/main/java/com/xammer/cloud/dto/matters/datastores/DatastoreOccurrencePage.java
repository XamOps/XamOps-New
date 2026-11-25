package com.xammer.cloud.dto.matters.datastores;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.xammer.cloud.service.matters.SampleMask;

import java.util.List;

/**
 * One page of an entity's occurrences (design 5.4 item 8). The only DTO that carries samples, masked
 * by {@link SampleMask} (build 9), so it is never cached and never logged.
 *
 * @param total see {@code DatastoreDrawerMapper.toOccurrences}.
 * @param skip  echoes the request.
 * @param limit echoes the request.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record DatastoreOccurrencePage(String entityName, long total, int skip, int limit, List<Occurrence> rows) {

    /** @param samples masked by {@link SampleMask} (build 9); never raw, never null. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Occurrence(String fileName, String url, String databaseName, String schemaName, String tableName,
                             String columnName, boolean falsePositive, List<String> samples) {
        /** Build 9: the half-rule floor; a value the mapper already masked is left unchanged. */
        public Occurrence {
            samples = SampleMask.maskAll(samples);
        }
        /** FR-API-6: a log line that prints this record shows how many samples it holds, never a sample. */
        @Override
        public String toString() {
            return "Occurrence[fileName=" + fileName + ", url=" + url + ", databaseName=" + databaseName
                    + ", schemaName=" + schemaName + ", tableName=" + tableName + ", columnName=" + columnName
                    + ", falsePositive=" + falsePositive + ", samples=<" + samples.size() + " values>]";
        }
    }
}
