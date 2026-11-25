package com.xammer.cloud.xampulse.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record XpChannel(Long id, Long accountId, String kind, String name, boolean configured, String summary) {}
