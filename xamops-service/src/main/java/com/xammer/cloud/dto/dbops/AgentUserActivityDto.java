package com.xammer.cloud.dto.dbops;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Maps one row from the agent's db-user-activity batch.
 * Sourced from pg_stat_activity (Postgres) or information_schema.PROCESSLIST (MySQL).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentUserActivityDto {

    private String  dsnAlias;
    private String  dbEngine;
    private String  dbHost;
    private String  username;

    private long    activeConnections;
    private long    idleConnections;
    private long    totalConnections;

    private long    callCount;          // cumulative queries by this user
    private double  totalTimeSeconds;   // cumulative execution time in seconds

    private String  collectedAt;

    // ── Getters / Setters ────────────────────────────────────────────────────

    public String getDsnAlias() { return dsnAlias; }
    public void setDsnAlias(String dsnAlias) { this.dsnAlias = dsnAlias; }

    public String getDbEngine() { return dbEngine; }
    public void setDbEngine(String dbEngine) { this.dbEngine = dbEngine; }

    public String getDbHost() { return dbHost; }
    public void setDbHost(String dbHost) { this.dbHost = dbHost; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public long getActiveConnections() { return activeConnections; }
    public void setActiveConnections(long activeConnections) { this.activeConnections = activeConnections; }

    public long getIdleConnections() { return idleConnections; }
    public void setIdleConnections(long idleConnections) { this.idleConnections = idleConnections; }

    public long getTotalConnections() { return totalConnections; }
    public void setTotalConnections(long totalConnections) { this.totalConnections = totalConnections; }

    public long getCallCount() { return callCount; }
    public void setCallCount(long callCount) { this.callCount = callCount; }

    public double getTotalTimeSeconds() { return totalTimeSeconds; }
    public void setTotalTimeSeconds(double totalTimeSeconds) { this.totalTimeSeconds = totalTimeSeconds; }

    public String getCollectedAt() { return collectedAt; }
    public void setCollectedAt(String collectedAt) { this.collectedAt = collectedAt; }
}
