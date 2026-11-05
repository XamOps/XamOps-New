package com.xammer.cloud.xampulse.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record XpError(String code, String message, String requestId, Map<String,Object> details) {}
