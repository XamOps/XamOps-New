package com.xammer.cloud.xampulse.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record XpEvent(Instant ts, String kind, Long accountId, String resource, String title, String detail) {}
