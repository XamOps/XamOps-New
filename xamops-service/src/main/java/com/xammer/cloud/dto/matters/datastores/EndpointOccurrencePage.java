package com.xammer.cloud.dto.matters.datastores;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * One page of an endpoint entity's occurrences (design 5.4 item 12). The only endpoint DTO that carries
 * raw samples (decision D2), so it is never cached and never logged.
 *
 * @param entityName echoes the request.
 * @param skip       echoes the request.
 * @param limit      echoes the request.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record EndpointOccurrencePage(String entityName, long total, int skip, int limit, List<Occurrence> rows) {

    /** @param samples raw values, as returned (D2); never null. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Occurrence(String fileName, String path, List<String> tags, boolean falsePositive,
                             List<String> samples) {

        /** As build 1's Occurrence: a log line shows how many samples the row holds, never a sample. */
        @Override
        public String toString() {
            return "Occurrence[fileName=" + fileName + ", path=" + path + ", tags=" + tags
                    + ", falsePositive=" + falsePositive + ", samples=<" + samples.size() + " values>]";
        }
    }
}
