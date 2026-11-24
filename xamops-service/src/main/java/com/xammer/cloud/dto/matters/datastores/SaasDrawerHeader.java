package com.xammer.cloud.dto.matters.datastores;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The SaaS drawer's header strip (design 5.4 item 1). The title and subtitle use the list row.
 *
 * @param driveType        GMAIL or GOOGLE_WORKSPACE: the Asset Type icon.
 * @param backfillState    upper-cased {@code scan_state}: the chip icon.
 * @param incrementalState upper-cased {@code realtime_scan_status}: the chip icon.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record SaasDrawerHeader(String driveType, String driveTypeLabel, String accountName, String backfillState,
                               String backfillLabel, String incrementalState, String incrementalLabel) {
}
