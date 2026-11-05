package com.xammer.cloud.xampulse.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record XpHostRow(Long accountId, String instanceId, String name, String region, String platform, String instanceType, String state, XpHealth health, Integer score, String reason, Double cpuPct, Double memPct, Double diskPct, Double netInBps, Double netOutBps, Double load1, List<Double> cpuSpark, Instant lastSeenAt, String agentStatus, String collectionLevel, Map<String,String> tags) {}
