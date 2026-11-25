package com.xammer.cloud.xampulse.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record XpRegionPlanRequest(String targetMode, List<String> instanceIds, String tagKey, String tagValue, Boolean confirmAllManaged) {}
