package com.xammer.cloud.dto.dbops;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentWaitEventDto {
    private String dsnAlias;
    private String dbEngine;
    private String dbHost;
    private String waitCategory;
    private String waitEventName;
    private int    sessionCount;
    private String collectedAt;

    public String getDsnAlias() { return dsnAlias; }
    public void setDsnAlias(String v) { this.dsnAlias = v; }
    public String getDbEngine() { return dbEngine; }
    public void setDbEngine(String v) { this.dbEngine = v; }
    public String getDbHost() { return dbHost; }
    public void setDbHost(String v) { this.dbHost = v; }
    public String getWaitCategory() { return waitCategory; }
    public void setWaitCategory(String v) { this.waitCategory = v; }
    public String getWaitEventName() { return waitEventName; }
    public void setWaitEventName(String v) { this.waitEventName = v; }
    public int getSessionCount() { return sessionCount; }
    public void setSessionCount(int v) { this.sessionCount = v; }
    public String getCollectedAt() { return collectedAt; }
    public void setCollectedAt(String v) { this.collectedAt = v; }
}
