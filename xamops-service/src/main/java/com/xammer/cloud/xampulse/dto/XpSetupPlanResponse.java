package com.xammer.cloud.xampulse.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record XpSetupPlanResponse(String planId, XpSetupPreview preview, List<XpStackLink> stacks, List<String> warnings) {}
