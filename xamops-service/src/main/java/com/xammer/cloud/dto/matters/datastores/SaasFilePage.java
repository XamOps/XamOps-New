package com.xammer.cloud.dto.matters.datastores;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * One page of the files behind a SaaS data element or document class (design 5.4 item 3).
 *
 * @param skip  echoes the request.
 * @param limit echoes the request.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record SaasFilePage(List<File> rows, int skip, int limit, long total) {

    /** @param id the 24-hex file id that opens the file drawer, or null when the vendor's is not one. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record File(String id, String name, long records) {
    }
}
