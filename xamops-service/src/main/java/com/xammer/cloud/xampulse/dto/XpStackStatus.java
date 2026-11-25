package com.xammer.cloud.xampulse.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record XpStackStatus(String region, String kind, String stackName, String status, String reason, List<String> events, Map<String, String> outputs) {}
