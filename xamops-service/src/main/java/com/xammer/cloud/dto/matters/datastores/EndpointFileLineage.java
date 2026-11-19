package com.xammer.cloud.dto.matters.datastores;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * The Endpoint file drawer's Lineage (F7; plan C19): the file's own lane from the endpoint-lineage route, then
 * build 2's hash-lineage copies.
 *
 * @param asset  null when the endpoint-lineage route knows no such file.
 * @param copies build 2's SaaS file lineage for the same file id.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record EndpointFileLineage(Asset asset, SaasFileLineage copies) {

    /**
     * @param firstSeenAt epoch seconds.
     * @param events      up to 100, API order, each with a null lane key.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Asset(String title, String device, String osUser, Long firstSeenAt,
                        List<SaasFileLineage.Event> events) {
    }
}
