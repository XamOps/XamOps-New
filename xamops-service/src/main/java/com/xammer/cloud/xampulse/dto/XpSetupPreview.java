package com.xammer.cloud.xampulse.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record XpSetupPreview(List<String> resources, List<String> iamActions, List<String> neverCollected, XpSnippets customerSnippets,
        XpCostEstimate customerCostEstimate, List<String> connectionRoleGrants, String templateSha256, String templateUrl) {}
