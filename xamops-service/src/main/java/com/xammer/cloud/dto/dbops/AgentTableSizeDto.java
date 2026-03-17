package com.xammer.cloud.dto.dbops;

/**
 * Storage usage for a single table — pushed as "db-table-sizes" batch by the DB agent.
 * Top 50 tables per DSN by total size.
 */
public class AgentTableSizeDto {

    private String dsnAlias;
    private String dbEngine;
    private String dbHost;
    private String schemaName;
    private String tableName;
    private long dataSizeBytes;
    private long indexSizeBytes;
    private long totalSizeBytes;
    private long rows;
    private String collectedAt;

    public String getDsnAlias() { return dsnAlias; }
    public void setDsnAlias(String dsnAlias) { this.dsnAlias = dsnAlias; }
    public String getDbEngine() { return dbEngine; }
    public void setDbEngine(String dbEngine) { this.dbEngine = dbEngine; }
    public String getDbHost() { return dbHost; }
    public void setDbHost(String dbHost) { this.dbHost = dbHost; }
    public String getSchemaName() { return schemaName; }
    public void setSchemaName(String schemaName) { this.schemaName = schemaName; }
    public String getTableName() { return tableName; }
    public void setTableName(String tableName) { this.tableName = tableName; }
    public long getDataSizeBytes() { return dataSizeBytes; }
    public void setDataSizeBytes(long dataSizeBytes) { this.dataSizeBytes = dataSizeBytes; }
    public long getIndexSizeBytes() { return indexSizeBytes; }
    public void setIndexSizeBytes(long indexSizeBytes) { this.indexSizeBytes = indexSizeBytes; }
    public long getTotalSizeBytes() { return totalSizeBytes; }
    public void setTotalSizeBytes(long totalSizeBytes) { this.totalSizeBytes = totalSizeBytes; }
    public long getRows() { return rows; }
    public void setRows(long rows) { this.rows = rows; }
    public String getCollectedAt() { return collectedAt; }
    public void setCollectedAt(String collectedAt) { this.collectedAt = collectedAt; }
}
