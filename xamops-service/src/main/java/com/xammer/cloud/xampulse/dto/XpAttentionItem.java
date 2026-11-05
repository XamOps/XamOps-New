package com.xammer.cloud.xampulse.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record XpAttentionItem(String id, String severity, String title, String detail, Long accountId, String instanceId, Instant since, String confidence, List<XpEvidence> evidence) {}
