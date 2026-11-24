package com.xammer.cloud.dto.matters.datastores;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A public-path file's lineage (build 7 design 4.6, 5.2): build 3's endpoint asset shape for both kinds.
 *
 * @param kind       {@code ENDPOINT} or {@code DRIVE}
 * @param asset      build 3's record; null when the route has no asset for the file
 * @param copies     endpoint: build 2's hash lineage; drive: {@link SaasFileLineage#unavailable()}
 * @param ownerEmail drive: {@code asset.owner_email}, the lane caption and First Seen actor; endpoint: null
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PublicFileLineage(String kind, EndpointFileLineage.Asset asset, SaasFileLineage copies,
                                String ownerEmail) {
}
