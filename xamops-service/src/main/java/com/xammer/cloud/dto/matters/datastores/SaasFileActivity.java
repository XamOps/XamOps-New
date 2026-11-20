package com.xammer.cloud.dto.matters.datastores;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * The SaaS file drawer's File Activity tab (design 5.4 item 6).
 *
 * @param entries newest first.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record SaasFileActivity(List<Entry> entries) {

    /** @param occurredAt epoch seconds. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Entry(String title, String status, String actor, Long occurredAt) {
    }
}
