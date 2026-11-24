package com.xammer.cloud.dto.matters.datastores;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Scan progress for the drawer's status chip (design 5.4 item 5).
 *
 * @param progress null unless the vendor's {@code scan_progress} is numeric.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record DatastoreScanProgress(Double progress) {
}
