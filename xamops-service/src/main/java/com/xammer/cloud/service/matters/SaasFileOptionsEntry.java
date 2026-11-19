package com.xammer.cloud.service.matters;

import com.xammer.cloud.dto.matters.datastores.DatastoreFilterOptions;

import java.util.List;
import java.util.Map;

/**
 * F2's cache entry (plan C3, C5): the SaaS tab's filter options and each category's document classes, so a
 * Category filter narrows Document Class without another vendor call. Only {@code options} reaches the browser.
 *
 * @param options           every dimension, Document Class unnarrowed.
 * @param classesByCategory category value → its class values, route order, de-duplicated.
 */
public record SaasFileOptionsEntry(DatastoreFilterOptions options, Map<String, List<String>> classesByCategory) {
}
