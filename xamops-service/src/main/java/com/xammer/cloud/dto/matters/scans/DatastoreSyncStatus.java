package com.xammer.cloud.dto.matters.scans;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A datastore's sync job (design 5.4).
 *
 * @param status    an upper-snake code, or null when there is no sync job
 * @param updatedAt epoch seconds
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record DatastoreSyncStatus(String status, Long updatedAt) {
}
