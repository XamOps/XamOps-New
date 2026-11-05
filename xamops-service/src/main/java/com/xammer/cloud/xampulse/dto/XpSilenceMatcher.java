package com.xammer.cloud.xampulse.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record XpSilenceMatcher(List<String> instanceIds, List<Long> ruleIds, List<String> severity) {}
