package com.xammer.cloud.dto.dbops;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Maps one row from the agent's db-replication batch (COL-A3).
 * Mirrors the Go DbReplication struct. Lag fields are nullable ({@code Double}) because a
 * standalone primary has no standby and a freshly-started replica has replayed nothing yet.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentReplicationDto {

    private String dsnAlias;
    private String dbEngine;
    private String dbHost;
    private String role;             // primary | replica
    private String clientAddr;
    private String applicationName;
    private String state;
    private Double writeLagSeconds;
    private Double flushLagSeconds;
    private Double replayLagSeconds;
    private String lastReplayTime;
    private String collectedAt;

    public String getDsnAlias() { return dsnAlias; }
    public void setDsnAlias(String v) { this.dsnAlias = v; }
    public String getDbEngine() { return dbEngine; }
    public void setDbEngine(String v) { this.dbEngine = v; }
    public String getDbHost() { return dbHost; }
    public void setDbHost(String v) { this.dbHost = v; }
    public String getRole() { return role; }
    public void setRole(String v) { this.role = v; }
    public String getClientAddr() { return clientAddr; }
    public void setClientAddr(String v) { this.clientAddr = v; }
    public String getApplicationName() { return applicationName; }
    public void setApplicationName(String v) { this.applicationName = v; }
    public String getState() { return state; }
    public void setState(String v) { this.state = v; }
    public Double getWriteLagSeconds() { return writeLagSeconds; }
    public void setWriteLagSeconds(Double v) { this.writeLagSeconds = v; }
    public Double getFlushLagSeconds() { return flushLagSeconds; }
    public void setFlushLagSeconds(Double v) { this.flushLagSeconds = v; }
    public Double getReplayLagSeconds() { return replayLagSeconds; }
    public void setReplayLagSeconds(Double v) { this.replayLagSeconds = v; }
    public String getLastReplayTime() { return lastReplayTime; }
    public void setLastReplayTime(String v) { this.lastReplayTime = v; }
    public String getCollectedAt() { return collectedAt; }
    public void setCollectedAt(String v) { this.collectedAt = v; }
}
