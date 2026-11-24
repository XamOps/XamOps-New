package com.xammer.cloud.xampulse.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record XpPattern(String patternId, String template, long count, Double deltaPct, boolean isNew, List<Long> trend, Instant firstSeen, String sampleId, Long sampleAccountId, XpSeverity severity) {}
