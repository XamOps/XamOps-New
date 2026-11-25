package com.xammer.cloud.xampulse.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record XpSetupInstances(int total, int ssmManaged, int inScope, int enrolled, int reporting, int notCovered) {}
