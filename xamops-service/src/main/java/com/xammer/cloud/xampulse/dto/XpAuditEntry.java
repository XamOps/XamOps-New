package com.xammer.cloud.xampulse.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record XpAuditEntry(Instant ts, Long userId, Long accountId, String action, String target, String ip) {}
