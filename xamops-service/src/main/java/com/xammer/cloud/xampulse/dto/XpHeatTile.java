package com.xammer.cloud.xampulse.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record XpHeatTile(Long accountId, String instanceId, String name, XpHealth health, Integer score, Double cpuPct, Double memPct) {}
