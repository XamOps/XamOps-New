package com.xammer.cloud.dto.dbops;

import com.xammer.cloud.domain.DbHealthAlert;

public class DbHealthAlertDto {
    private Long   id;
    private String instanceId;
    private String alertType;
    private String severity;
    private String message;
    private String triggeredAt;
    private String resolvedAt;
    private boolean active;

    public static DbHealthAlertDto from(DbHealthAlert a) {
        DbHealthAlertDto d = new DbHealthAlertDto();
        d.id          = a.getId();
        d.instanceId  = a.getInstanceId();
        d.alertType   = a.getAlertType();
        d.severity    = a.getSeverity();
        d.message     = a.getMessage();
        d.triggeredAt = a.getTriggeredAt().toString();
        d.resolvedAt  = a.getResolvedAt() != null ? a.getResolvedAt().toString() : null;
        d.active      = a.isActive();
        return d;
    }

    public Long getId()           { return id; }
    public String getInstanceId() { return instanceId; }
    public String getAlertType()  { return alertType; }
    public String getSeverity()   { return severity; }
    public String getMessage()    { return message; }
    public String getTriggeredAt(){ return triggeredAt; }
    public String getResolvedAt() { return resolvedAt; }
    public boolean isActive()     { return active; }
}
