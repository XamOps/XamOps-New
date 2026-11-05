package com.xammer.cloud.xampulse.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record XpIncident(Long id, Long accountId, String title, String severity, String state, Instant openedAt, Instant resolvedAt, String ackBy, List<Long> ruleIds, List<String> instanceIds, String summary, List<XpTimelineEntry> timeline) {}
