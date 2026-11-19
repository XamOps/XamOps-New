package com.xammer.cloud.dto.matters.datastores;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * The Endpoint file drawer's header, card, Sensitives and Details (F5; design 5.4 as changed by plan C15 to
 * C17). No sample: the vendor's {@code sample_matches} is never read.
 *
 * @param extension          lower-cased: the header icon and, upper-cased, the subtitle.
 * @param categoryLabel      each word's first letter upper-cased.
 * @param documentClassLabel as returned.
 * @param device             the agent's hostname.
 * @param modifiedAt         epoch seconds, null when not positive; so are fileCreatedAt and accessedAt.
 * @param sensitives         API order.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record EndpointFileOverview(String name, String extension, String owner, String categoryLabel,
                                   String documentClassLabel, long sensitiveRecords, String path, Long sizeBytes,
                                   String device, Long modifiedAt, Long fileCreatedAt, Long accessedAt,
                                   List<String> tags, List<Sensitive> sensitives) {

    /** @param categoryLabel the first label code's category: "Personal". */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Sensitive(String dataElement, String categoryLabel, long count) {
    }
}
