package com.xammer.cloud.dto.sre;

import com.xammer.cloud.domain.SreInvestigation;
import lombok.Data;

@Data
public class TriggerInvestigationRequest {
    private Long cloudAccountId;
    private SreInvestigation.ResourceType resourceType;
    private String resourceId;
    private String resourceName;
    private String alertPayload;
}
