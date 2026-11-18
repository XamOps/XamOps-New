package com.xammer.cloud.dto.matters.datastores;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Cache-only ({@code dsepsum}), never returned, as {@link DatastoreSummarySnapshot}: the endpoint summary
 * and the entity names behind the occurrences' {@code entityName} check (design 5.4 items 10 and 12).
 *
 * @param entityNames the ENTITY metrics of the unfiltered call, in API order.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record EndpointSummarySnapshot(EndpointSummary summary, List<String> entityNames) {
}
