package com.xammer.cloud.dto.matters.datastores;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * The Endpoint drawer's Entity View rows (design 5.4 item 11).
 *
 * @param metrics by count descending; ties keep API order.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record EndpointSensitiveMetrics(List<EndpointMetric> metrics) {
}
