package com.xammer.cloud.xampulse.dto.agent;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record HeartbeatRequest(String agentVersion, String collectorVersion, Long uptimeSec, Queue queue,
        List<SourceStatus> sourcesStatus, List<String> errors) {
    public record Queue(Long bytes, Long dropped) {}
    public record SourceStatus(String source, String state, String error) {}
}
