package com.xammer.cloud.dto.matters.datastores;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** The Risk tab's misconfigurations (design 5.4 item 9, rules in 5.6.7). */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record DatastoreRisks(List<Misconfiguration> items) {

    /** @param cli null when blank. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Misconfiguration(String title, String source, String severity, String severityLabel,
                                   String description, String remediation, String cli) {
    }
}
