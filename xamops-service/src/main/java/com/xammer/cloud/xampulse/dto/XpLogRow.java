package com.xammer.cloud.xampulse.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record XpLogRow(String id, Instant ts, Long accountId, String region, String instanceId, String service, String source, XpSeverity severity, String body, Map<String,String> attrs, String traceId, String containerId) {}
