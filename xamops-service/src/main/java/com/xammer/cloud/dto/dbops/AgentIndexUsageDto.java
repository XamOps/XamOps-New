package com.xammer.cloud.dto.dbops;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Maps one row from the agent's db-index-usage batch.
 * Postgres: pg_stat_user_indexes. MySQL: performance_schema.table_io_waits_summary_by_index_usage.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentIndexUsageDto {

    private String  dsnAlias;
    private String  dbEngine;
    private String  dbHost;
    private String  schemaName;
    private String  tableName;
    private String  indexName;

    private long    idxScan;
    private long    idxTupRead;
    private long    idxTupFetch;
    @JsonProperty("isUnused")
    private boolean isUnused;
    private long    indexSizeBytes;

    private String  collectedAt;

    // ── Getters / Setters ────────────────────────────────────────────────────

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

    public String getIndexName() { return indexName; }
    public void setIndexName(String indexName) { this.indexName = indexName; }

    public long getIdxScan() { return idxScan; }
    public void setIdxScan(long idxScan) { this.idxScan = idxScan; }

    public long getIdxTupRead() { return idxTupRead; }
    public void setIdxTupRead(long idxTupRead) { this.idxTupRead = idxTupRead; }

    public long getIdxTupFetch() { return idxTupFetch; }
    public void setIdxTupFetch(long idxTupFetch) { this.idxTupFetch = idxTupFetch; }

    public boolean isUnused() { return isUnused; }
    public void setUnused(boolean unused) { isUnused = unused; }

    public long getIndexSizeBytes() { return indexSizeBytes; }
    public void setIndexSizeBytes(long indexSizeBytes) { this.indexSizeBytes = indexSizeBytes; }

    public String getCollectedAt() { return collectedAt; }
    public void setCollectedAt(String collectedAt) { this.collectedAt = collectedAt; }
}
