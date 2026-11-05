package com.xammer.cloud.dto.dbops;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Maps one row from the agent's db-lock-waits batch (COL-A2).
 * Mirrors the Go DbLockWait struct in xamops-agent/internal/collector/types.go.
 *
 * <p>SEC invariant: this DTO carries NO query / statement text — only session identity, wait and
 * age fields. {@code queryId} is the statement hash only, never the statement text.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentLockWaitDto {

    private String dsnAlias;
    private String dbEngine;
    private String dbHost;
    private String category;          // blocked | blocking | long_running | idle_in_transaction
    private int    pid;
    private String usename;
    private String applicationName;
    private String state;
    private String waitEventType;
    private String waitEvent;
    private String backendXid;
    private String xactStart;
    private String queryStart;
    private String stateChange;
    private double xactAgeSeconds;
    private String blockedByPids;
    private String queryId;
    private String collectedAt;

    public String getDsnAlias() { return dsnAlias; }
    public void setDsnAlias(String v) { this.dsnAlias = v; }
    public String getDbEngine() { return dbEngine; }
    public void setDbEngine(String v) { this.dbEngine = v; }
    public String getDbHost() { return dbHost; }
    public void setDbHost(String v) { this.dbHost = v; }
    public String getCategory() { return category; }
    public void setCategory(String v) { this.category = v; }
    public int getPid() { return pid; }
    public void setPid(int v) { this.pid = v; }
    public String getUsename() { return usename; }
    public void setUsename(String v) { this.usename = v; }
    public String getApplicationName() { return applicationName; }
    public void setApplicationName(String v) { this.applicationName = v; }
    public String getState() { return state; }
    public void setState(String v) { this.state = v; }
    public String getWaitEventType() { return waitEventType; }
    public void setWaitEventType(String v) { this.waitEventType = v; }
    public String getWaitEvent() { return waitEvent; }
    public void setWaitEvent(String v) { this.waitEvent = v; }
    public String getBackendXid() { return backendXid; }
    public void setBackendXid(String v) { this.backendXid = v; }
    public String getXactStart() { return xactStart; }
    public void setXactStart(String v) { this.xactStart = v; }
    public String getQueryStart() { return queryStart; }
    public void setQueryStart(String v) { this.queryStart = v; }
    public String getStateChange() { return stateChange; }
    public void setStateChange(String v) { this.stateChange = v; }
    public double getXactAgeSeconds() { return xactAgeSeconds; }
    public void setXactAgeSeconds(double v) { this.xactAgeSeconds = v; }
    public String getBlockedByPids() { return blockedByPids; }
    public void setBlockedByPids(String v) { this.blockedByPids = v; }
    public String getQueryId() { return queryId; }
    public void setQueryId(String v) { this.queryId = v; }
    public String getCollectedAt() { return collectedAt; }
    public void setCollectedAt(String v) { this.collectedAt = v; }
}
