package com.xammer.cloud.xampulse.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record XpSetupRegion(String region, int candidateInstances, String stackState, String targetMode, int covered, int reporting) {}
