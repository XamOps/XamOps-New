package com.xammer.cloud.dto.dbops;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Maps one row from the agent's db-query-stats batch.
 * Mirrors the Go DbQueryStat struct in xamops-agent/internal/collector/types.go
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentQueryStatDto {

    // Connection info
    private String dsnAlias;   // e.g. "db-0"
    private String dbEngine;   // "mysql" | "postgres"
    private String dbHost;     // hostname only, no credentials

    // Query identity
    private String queryDigest;

    // Execution stats
    private long   callCount;
    private double totalTimeSeconds;
    private double avgTimeSeconds;
    private double maxTimeSeconds;

    // Row stats
    private long rowsExamined;   // MySQL only
    private long rowsSent;       // MySQL: rows_sent / Postgres: rows

    // I/O stats (Postgres)
    private long sharedBlksHit;
    private long sharedBlksRead;

    // Flags
    private boolean noIndexUsed;
    private double  lockTimeSeconds;

    // When the agent collected this batch (stamped by the server from the cache row)
    private String collectedAt;

    // ── Getters / Setters ────────────────────────────────────────────────────

    public String getDsnAlias() { return dsnAlias; }
    public void setDsnAlias(String dsnAlias) { this.dsnAlias = dsnAlias; }

    public String getDbEngine() { return dbEngine; }
    public void setDbEngine(String dbEngine) { this.dbEngine = dbEngine; }

    public String getDbHost() { return dbHost; }
    public void setDbHost(String dbHost) { this.dbHost = dbHost; }

    public String getQueryDigest() { return queryDigest; }
    public void setQueryDigest(String queryDigest) { this.queryDigest = queryDigest; }

    public long getCallCount() { return callCount; }
    public void setCallCount(long callCount) { this.callCount = callCount; }

    public double getTotalTimeSeconds() { return totalTimeSeconds; }
    public void setTotalTimeSeconds(double totalTimeSeconds) { this.totalTimeSeconds = totalTimeSeconds; }

    public double getAvgTimeSeconds() { return avgTimeSeconds; }
    public void setAvgTimeSeconds(double avgTimeSeconds) { this.avgTimeSeconds = avgTimeSeconds; }

    public double getMaxTimeSeconds() { return maxTimeSeconds; }
    public void setMaxTimeSeconds(double maxTimeSeconds) { this.maxTimeSeconds = maxTimeSeconds; }

    public long getRowsExamined() { return rowsExamined; }
    public void setRowsExamined(long rowsExamined) { this.rowsExamined = rowsExamined; }

    public long getRowsSent() { return rowsSent; }
    public void setRowsSent(long rowsSent) { this.rowsSent = rowsSent; }

    public long getSharedBlksHit() { return sharedBlksHit; }
    public void setSharedBlksHit(long sharedBlksHit) { this.sharedBlksHit = sharedBlksHit; }

    public long getSharedBlksRead() { return sharedBlksRead; }
    public void setSharedBlksRead(long sharedBlksRead) { this.sharedBlksRead = sharedBlksRead; }

    public boolean isNoIndexUsed() { return noIndexUsed; }
    public void setNoIndexUsed(boolean noIndexUsed) { this.noIndexUsed = noIndexUsed; }

    public double getLockTimeSeconds() { return lockTimeSeconds; }
    public void setLockTimeSeconds(double lockTimeSeconds) { this.lockTimeSeconds = lockTimeSeconds; }

    public String getCollectedAt() { return collectedAt; }
    public void setCollectedAt(String collectedAt) { this.collectedAt = collectedAt; }
}
