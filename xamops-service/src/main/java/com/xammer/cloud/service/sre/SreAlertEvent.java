package com.xammer.cloud.service.sre;

import com.xammer.cloud.domain.SreInvestigation;
import org.springframework.context.ApplicationEvent;

public class SreAlertEvent extends ApplicationEvent {

    private final Long clientId;
    private final Long cloudAccountId;
    private final SreInvestigation.ResourceType resourceType;
    private final String resourceId;
    private final String resourceName;
    private final String severity;
    private final String alertPayload;

    public SreAlertEvent(Object source,
                         Long clientId,
                         Long cloudAccountId,
                         SreInvestigation.ResourceType resourceType,
                         String resourceId,
                         String resourceName,
                         String severity,
                         String alertPayload) {
        super(source);
        this.clientId = clientId;
        this.cloudAccountId = cloudAccountId;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.resourceName = resourceName;
        this.severity = severity;
        this.alertPayload = alertPayload;
    }

    public Long getClientId() { return clientId; }
    public Long getCloudAccountId() { return cloudAccountId; }
    public SreInvestigation.ResourceType getResourceType() { return resourceType; }
    public String getResourceId() { return resourceId; }
    public String getResourceName() { return resourceName; }
    public String getSeverity() { return severity; }
    public String getAlertPayload() { return alertPayload; }
}
