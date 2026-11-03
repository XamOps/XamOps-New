package com.xammer.cloud.dto.dbops;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * The agent's db-fk-graph document for ONE DSN (COL-C1). Stored latest-wins as a single JSON object
 * under cache key {@code db-fk-graph:{cloudAccountId}:{dsnAlias}} (saveDocPerDsn). Mirrors Go
 * DbFkGraphDoc. The API-C1 builder derives the cytoscape {@link FkGraphDoc} response from this.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentFkGraphDoc {

    private String dsnAlias;
    private String dbEngine;
    private String dbHost;
    private String collectedAt;
    private List<AgentFkTableDto> tables;
    private List<AgentFkEdgeDto> fkEdges;
    private boolean truncated;
    private int totalFkTables;
    private int totalFkEdges;

    public String getDsnAlias() { return dsnAlias; }
    public void setDsnAlias(String v) { this.dsnAlias = v; }
    public String getDbEngine() { return dbEngine; }
    public void setDbEngine(String v) { this.dbEngine = v; }
    public String getDbHost() { return dbHost; }
    public void setDbHost(String v) { this.dbHost = v; }
    public String getCollectedAt() { return collectedAt; }
    public void setCollectedAt(String v) { this.collectedAt = v; }
    public List<AgentFkTableDto> getTables() { return tables; }
    public void setTables(List<AgentFkTableDto> v) { this.tables = v; }
    public List<AgentFkEdgeDto> getFkEdges() { return fkEdges; }
    public void setFkEdges(List<AgentFkEdgeDto> v) { this.fkEdges = v; }
    public boolean isTruncated() { return truncated; }
    public void setTruncated(boolean v) { this.truncated = v; }
    public int getTotalFkTables() { return totalFkTables; }
    public void setTotalFkTables(int v) { this.totalFkTables = v; }
    public int getTotalFkEdges() { return totalFkEdges; }
    public void setTotalFkEdges(int v) { this.totalFkEdges = v; }
}
