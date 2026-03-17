package com.xammer.cloud.dto.dbops;

import java.util.List;

/**
 * Time-series snapshots from a DB agent's db-instance-metrics pushes.
 * Used to render connection, QPS, buffer pool, and slow query charts.
 */
public class AgentTimeseriesDto {

    private String agentName;
    private String dbHost;
    private List<SnapshotPoint> snapshots;

    public static class SnapshotPoint {
        private String ts;                    // ISO-8601 collected_at
        private long connections;
        private long maxConnections;
        private double bufferPoolHitPct;
        private long totalQueries;            // cumulative — frontend computes delta for QPS
        private long totalSlowQueries;
        private long comSelect;
        private long comInsert;
        private long comUpdate;
        private long comDelete;
        private long innodbRowLockWaits;  // cumulative
        private long innodbDeadlocks;     // cumulative
        private long threadsRunning;      // instantaneous

        public String getTs() { return ts; }
        public void setTs(String ts) { this.ts = ts; }
        public long getConnections() { return connections; }
        public void setConnections(long connections) { this.connections = connections; }
        public long getMaxConnections() { return maxConnections; }
        public void setMaxConnections(long maxConnections) { this.maxConnections = maxConnections; }
        public double getBufferPoolHitPct() { return bufferPoolHitPct; }
        public void setBufferPoolHitPct(double bufferPoolHitPct) { this.bufferPoolHitPct = bufferPoolHitPct; }
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
        public long getInnodbRowLockWaits() { return innodbRowLockWaits; }
        public void setInnodbRowLockWaits(long innodbRowLockWaits) { this.innodbRowLockWaits = innodbRowLockWaits; }
        public long getInnodbDeadlocks() { return innodbDeadlocks; }
        public void setInnodbDeadlocks(long innodbDeadlocks) { this.innodbDeadlocks = innodbDeadlocks; }
        public long getThreadsRunning() { return threadsRunning; }
        public void setThreadsRunning(long threadsRunning) { this.threadsRunning = threadsRunning; }
    }

    public String getAgentName() { return agentName; }
    public void setAgentName(String agentName) { this.agentName = agentName; }
    public String getDbHost() { return dbHost; }
    public void setDbHost(String dbHost) { this.dbHost = dbHost; }
    public List<SnapshotPoint> getSnapshots() { return snapshots; }
    public void setSnapshots(List<SnapshotPoint> snapshots) { this.snapshots = snapshots; }
}
