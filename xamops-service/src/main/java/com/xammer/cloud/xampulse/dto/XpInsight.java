package com.xammer.cloud.xampulse.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record XpInsight(String id, String kind, String severity, String title, String body, Long accountId, String instanceId, List<XpEvidence> evidence, String confidence, Instant asOf) {}
