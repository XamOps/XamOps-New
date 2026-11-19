package com.xammer.cloud.dto.matters.datastores;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * One keyset page of the Files page SaaS tab (F1; design 5.4 as changed by plan C7, C10, C12). There is no
 * total: the vendor route returns cursors only.
 *
 * @param rows       in API order.
 * @param limit      echoes the request.
 * @param nextCursor the vendor's next cursor, unchanged; null on the last page or when it fails the cursor rule.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record SaasFileListPage(List<Row> rows, int limit, String nextCursor) {

    /**
     * @param id                 the 24-hex file id, lower-cased, or null: opens the SaaS file drawer.
     * @param extension          a short file extension, lower-cased, or null (GAP-30: Gmail subjects).
     * @param source             GMAIL, GOOGLE_WORKSPACE…: the icon under the name and the drawer's tab set.
     * @param sourceLabel        "Gmail", "Google Drive".
     * @param categoryLabel      each word's first letter upper-cased: "Other", "IT &amp; Technical".
     * @param documentClassLabel as returned.
     * @param sharingModes       display order, never empty: "Private" when nothing applies.
     * @param lastModifiedAt     epoch seconds, null when not positive.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Row(String id, String name, String extension, String source, String sourceLabel,
                      String categoryLabel, String documentClassLabel, List<SharingMode> sharingModes, String owner,
                      Long lastModifiedAt) {
    }

    /** @param code PUBLIC, EXTERNAL, INTERNAL or PRIVATE. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SharingMode(String code, String label) {
    }
}
