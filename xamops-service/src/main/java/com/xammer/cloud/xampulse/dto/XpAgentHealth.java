package com.xammer.cloud.xampulse.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/** One agent as the fleet view shows it. {@code status} is ACTIVE, PENDING, STALE or REVOKED. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record XpAgentHealth(String instanceId, String name, String status, String version, Instant lastSeenAt,
                            Long queueBytes, Long droppedTotal, List<Source> sources, List<String> errors) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Source(String source, String state, String error) {}
}
