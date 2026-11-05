package com.xammer.cloud.xampulse.dto.agent;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentConfig(long configVersion, String level, List<Source> sources, List<RedactionRule> redaction, Limits limits, String gatewayUrl) {
    public record Source(String kind, List<String> paths, String parser, String multiline) {}
    public record RedactionRule(String rule, String pattern, String replacement) {}
    public record Limits(long maxBytesPerSec, long queueMaxBytes) {}
}
