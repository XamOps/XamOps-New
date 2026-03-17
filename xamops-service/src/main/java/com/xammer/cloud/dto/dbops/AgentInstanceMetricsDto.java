package com.xammer.cloud.dto.dbops;

/**
 * Server-level metrics collected by the XamOps DB agent via SHOW GLOBAL STATUS (MySQL)
 * or pg_stat_activity / pg_stat_database (PostgreSQL).
 * Pushed as "db-instance-metrics" batch by the agent.
 */
public class AgentInstanceMetricsDto {

    private String dsnAlias;
    private String dbEngine;
    private String dbHost;

    // Connectivity
    private long currentConnections;
    private long maxConnections;
    private long maxUsedConnections;

    // Throughput (cumulative counters)
    private long totalQueries;
    private long totalSlowQueries;
    private long comSelect;
    private long comInsert;
    private long comUpdate;
    private long comDelete;

    // InnoDB (MySQL only)
    private long innodbBufferPoolReads;
    private double innodbBufferPoolHitPct;

    // Locking (cumulative counters)
    private long innodbRowLockWaits;
    private long innodbDeadlocks;
    private long threadsRunning;

    // Network
    private long bytesReceived;
    private long bytesSent;

    // Server info
    private long uptime;
    private String version;
    private String collectedAt;

    // Getters and setters
    public String getDsnAlias() { return dsnAlias; }
    public void setDsnAlias(String dsnAlias) { this.dsnAlias = dsnAlias; }
    public String getDbEngine() { return dbEngine; }
    public void setDbEngine(String dbEngine) { this.dbEngine = dbEngine; }
    public String getDbHost() { return dbHost; }
    public void setDbHost(String dbHost) { this.dbHost = dbHost; }
    public long getCurrentConnections() { return currentConnections; }
    public void setCurrentConnections(long currentConnections) { this.currentConnections = currentConnections; }
    public long getMaxConnections() { return maxConnections; }
    public void setMaxConnections(long maxConnections) { this.maxConnections = maxConnections; }
    public long getMaxUsedConnections() { return maxUsedConnections; }
    public void setMaxUsedConnections(long maxUsedConnections) { this.maxUsedConnections = maxUsedConnections; }
    public long getTotalQueries() { return totalQueries; }
    public void setTotalQueries(long totalQueries) { this.totalQueries = totalQueries; }
    public long getTotalSlowQueries() { return totalSlowQueries; }
    public void setTotalSlowQueries(long totalSlowQueries) { this.totalSlowQueries = totalSlowQueries; }
    public long getComSelect() { return comSelect; }
    public void setComSelect(long comSelect) { this.comSelect = comSelect; }
    public long getComInsert() { return comInsert; }
    public void setComInsert(long comInsert) { this.comInsert = comInsert; }
    public long getComUpdate() { return comUpdate; }
    public void setComUpdate(long comUpdate) { this.comUpdate = comUpdate; }
    public long getComDelete() { return comDelete; }
    public void setComDelete(long comDelete) { this.comDelete = comDelete; }
    public long getInnodbBufferPoolReads() { return innodbBufferPoolReads; }
    public void setInnodbBufferPoolReads(long innodbBufferPoolReads) { this.innodbBufferPoolReads = innodbBufferPoolReads; }
    public double getInnodbBufferPoolHitPct() { return innodbBufferPoolHitPct; }
    public void setInnodbBufferPoolHitPct(double innodbBufferPoolHitPct) { this.innodbBufferPoolHitPct = innodbBufferPoolHitPct; }
    public long getInnodbRowLockWaits() { return innodbRowLockWaits; }
    public void setInnodbRowLockWaits(long innodbRowLockWaits) { this.innodbRowLockWaits = innodbRowLockWaits; }
    public long getInnodbDeadlocks() { return innodbDeadlocks; }
    public void setInnodbDeadlocks(long innodbDeadlocks) { this.innodbDeadlocks = innodbDeadlocks; }
    public long getThreadsRunning() { return threadsRunning; }
    public void setThreadsRunning(long threadsRunning) { this.threadsRunning = threadsRunning; }
    public long getBytesReceived() { return bytesReceived; }
    public void setBytesReceived(long bytesReceived) { this.bytesReceived = bytesReceived; }
    public long getBytesSent() { return bytesSent; }
    public void setBytesSent(long bytesSent) { this.bytesSent = bytesSent; }
    public long getUptime() { return uptime; }
    public void setUptime(long uptime) { this.uptime = uptime; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String getCollectedAt() { return collectedAt; }
    public void setCollectedAt(String collectedAt) { this.collectedAt = collectedAt; }
}
