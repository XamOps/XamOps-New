package com.xammer.cloud.dto.matters.datastores;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One Entity View row of the Endpoint drawer (design 5.4 item 11).
 *
 * @param kind ENTITY or DOCUMENT_CLASS: the row layout and the chip colour.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record EndpointMetric(String kind, String name, String categoryLabel, long count) {
}
