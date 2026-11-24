package com.xammer.cloud.dto.matters.pages;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** The Assists page (design 5.4): the project's on-prem connectors. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AssistList(List<Assist> assists) {

    /**
     * @param clientId        the connector's client identifier, shown by the console; null when credential-shaped
     * @param secretExpiresAt epoch seconds
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Assist(String id, String name, String clientId, String healthStatus, String statusLabel,
                         String assistType, String assistTypeLabel, String version, Long secretExpiresAt) {
    }
}
