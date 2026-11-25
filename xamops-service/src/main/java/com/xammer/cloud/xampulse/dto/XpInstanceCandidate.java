package com.xammer.cloud.xampulse.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record XpInstanceCandidate(String instanceId, String name, String region, String platform, String state,
        Map<String, String> tags, Boolean ssmManaged, boolean inScope, String collectionLevel, XpAgentInfo agent) {}
