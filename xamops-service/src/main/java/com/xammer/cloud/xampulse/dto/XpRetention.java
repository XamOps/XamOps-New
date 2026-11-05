package com.xammer.cloud.xampulse.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record XpRetention(int hotDays, int iaAfterDays, int glacierIrAfterDays, int expireAfterDays) {}
