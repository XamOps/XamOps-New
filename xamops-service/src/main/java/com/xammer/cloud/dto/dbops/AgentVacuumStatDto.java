package com.xammer.cloud.dto.dbops;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Maps one row from the agent's db-vacuum-stats batch.
 * PostgreSQL only — sourced from pg_stat_user_tables.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentVacuumStatDto {

    private String dsnAlias;
    private String dbEngine;
    private String dbHost;
    private String schemaName;
    private String tableName;

    @JsonProperty("nLiveTup")
    private long    nLiveTup;
    @JsonProperty("nDeadTup")
    private long    nDeadTup;
    private double  deadTupRatio;      // nDeadTup / (nLiveTup + nDeadTup)

    private String  lastVacuum;
    private String  lastAutoVacuum;
    private String  lastAnalyze;
    private String  lastAutoAnalyze;

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

    public long getNLiveTup() { return nLiveTup; }
    public void setNLiveTup(long nLiveTup) { this.nLiveTup = nLiveTup; }

    public long getNDeadTup() { return nDeadTup; }
    public void setNDeadTup(long nDeadTup) { this.nDeadTup = nDeadTup; }

    public double getDeadTupRatio() { return deadTupRatio; }
    public void setDeadTupRatio(double deadTupRatio) { this.deadTupRatio = deadTupRatio; }

    public String getLastVacuum() { return lastVacuum; }
    public void setLastVacuum(String lastVacuum) { this.lastVacuum = lastVacuum; }

    public String getLastAutoVacuum() { return lastAutoVacuum; }
    public void setLastAutoVacuum(String lastAutoVacuum) { this.lastAutoVacuum = lastAutoVacuum; }

    public String getLastAnalyze() { return lastAnalyze; }
    public void setLastAnalyze(String lastAnalyze) { this.lastAnalyze = lastAnalyze; }

    public String getLastAutoAnalyze() { return lastAutoAnalyze; }
    public void setLastAutoAnalyze(String lastAutoAnalyze) { this.lastAutoAnalyze = lastAutoAnalyze; }

    public String getCollectedAt() { return collectedAt; }
    public void setCollectedAt(String collectedAt) { this.collectedAt = collectedAt; }
}
