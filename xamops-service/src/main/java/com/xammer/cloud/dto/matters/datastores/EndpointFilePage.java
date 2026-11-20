package com.xammer.cloud.dto.matters.datastores;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * One page of the Endpoint File View or of a document class's files (design 5.4 item 13). No samples.
 *
 * @param skip  echoes the request.
 * @param limit echoes the request.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record EndpointFilePage(List<File> rows, int skip, int limit, long total) {

    /**
     * @param id                 the endpoint file's UUID, lower-cased, or null: opens the Endpoint file drawer.
     * @param extension          the file icon.
     * @param labels             display labels.
     * @param categoryLabel      title case: "It &amp; Technical".
     * @param documentClassLabel title case: "System Documentation".
     * @param modifiedAt         epoch seconds, null when not positive.
     * @param scannedAt          epoch seconds of the vendor's {@code updatedAt}: the drawer's Last Scanned.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record File(String id, String name, String extension, String path, String user, Long sizeBytes,
                       long sensitiveCount, List<String> labels, String categoryLabel, String documentClassLabel,
                       List<String> tags, Long modifiedAt, Long scannedAt) {
    }
}
