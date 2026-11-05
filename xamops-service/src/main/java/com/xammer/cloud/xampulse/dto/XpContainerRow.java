package com.xammer.cloud.xampulse.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record XpContainerRow(Long accountId, String instanceId, String containerId, String name, String image, Double cpuPct, Double memBytes, Instant lastSeenAt) {}
