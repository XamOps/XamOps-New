package com.xammer.cloud.dto.dbops;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Maps one row from the agent's db-table-activity batch.
 * Postgres: pg_stat_user_tables. MySQL: performance_schema.table_io_waits_summary_by_table.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentTableActivityDto {

    private String dsnAlias;
    private String dbEngine;
    private String dbHost;
    private String schemaName;
    private String tableName;

    private long seqScan;       // full-table sequential scans
    private long seqTupRead;    // rows read by seq scans (Postgres)
    private long idxScan;       // index scans
    private long idxTupFetch;   // rows fetched via index (Postgres)
    @JsonProperty("nTupIns")  private long nTupIns;
    @JsonProperty("nTupUpd")  private long nTupUpd;
    @JsonProperty("nTupDel")  private long nTupDel;
    @JsonProperty("nTupHotUpd") private long nTupHotUpd;    // HOT updates — no index overhead (Postgres)

    private String collectedAt;

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

    public long getSeqScan() { return seqScan; }
    public void setSeqScan(long seqScan) { this.seqScan = seqScan; }

    public long getSeqTupRead() { return seqTupRead; }
    public void setSeqTupRead(long seqTupRead) { this.seqTupRead = seqTupRead; }

    public long getIdxScan() { return idxScan; }
    public void setIdxScan(long idxScan) { this.idxScan = idxScan; }

    public long getIdxTupFetch() { return idxTupFetch; }
    public void setIdxTupFetch(long idxTupFetch) { this.idxTupFetch = idxTupFetch; }

    public long getNTupIns() { return nTupIns; }
    public void setNTupIns(long nTupIns) { this.nTupIns = nTupIns; }

    public long getNTupUpd() { return nTupUpd; }
    public void setNTupUpd(long nTupUpd) { this.nTupUpd = nTupUpd; }

    public long getNTupDel() { return nTupDel; }
    public void setNTupDel(long nTupDel) { this.nTupDel = nTupDel; }

    public long getNTupHotUpd() { return nTupHotUpd; }
    public void setNTupHotUpd(long nTupHotUpd) { this.nTupHotUpd = nTupHotUpd; }

    public String getCollectedAt() { return collectedAt; }
    public void setCollectedAt(String collectedAt) { this.collectedAt = collectedAt; }
}
