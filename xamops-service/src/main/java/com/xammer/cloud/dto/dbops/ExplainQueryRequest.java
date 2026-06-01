package com.xammer.cloud.dto.dbops;

public class ExplainQueryRequest {
    private String agentName;
    private String dsnAlias;
    private String queryText;
    private String dbEngine;

    public String getAgentName() { return agentName; }
    public void setAgentName(String v) { this.agentName = v; }
    public String getDsnAlias() { return dsnAlias; }
    public void setDsnAlias(String v) { this.dsnAlias = v; }
    public String getQueryText() { return queryText; }
    public void setQueryText(String v) { this.queryText = v; }
    public String getDbEngine() { return dbEngine; }
    public void setDbEngine(String v) { this.dbEngine = v; }
}
