package com.xammer.cloud.xampulse.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record XpTrustPanel(List<String> collected, List<String> neverCollected, List<XpRedaction> redaction, XpTrustWhere where, List<String> access, List<String> controls, XpPermissionsDiff permissionsDiff, String templateSha256) {}
