package com.xammer.cloud.dto.matters.datastores;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.xammer.cloud.service.matters.SampleMask;

import java.util.List;

/**
 * One page of an endpoint entity's occurrences (design 5.4 item 12). The only endpoint DTO that carries
 * samples, masked by {@link SampleMask} (build 9), so it is never cached and never logged.
 *
 * @param entityName echoes the request.
 * @param skip       echoes the request.
 * @param limit      echoes the request.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record EndpointOccurrencePage(String entityName, long total, int skip, int limit, List<Occurrence> rows) {

    /** @param samples masked by {@link SampleMask} (build 9); never raw, never null. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Occurrence(String fileName, String path, List<String> tags, boolean falsePositive,
                             List<String> samples) {
        /** Build 9: the half-rule floor; a value the mapper already masked is left unchanged. */
        public Occurrence {
            samples = SampleMask.maskAll(samples);
        }

        /** As build 1's Occurrence: a log line shows how many samples the row holds, never a sample. */
        @Override
        public String toString() {
            return "Occurrence[fileName=" + fileName + ", path=" + path + ", tags=" + tags
                    + ", falsePositive=" + falsePositive + ", samples=<" + samples.size() + " values>]";
        }
    }
}
