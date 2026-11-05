package com.xammer.cloud.xampulse.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record XpLogSourceView(Long id, String kind, List<String> paths, String parser, boolean enabled, List<RedactionRule> redaction) {
    public record RedactionRule(String rule, String pattern) {}
}
