package com.xammer.cloud.dto.dbops;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentDiagnosticDto {
    private String  dsnAlias;
    private String  dbEngine;
    private String  dbHost;
    private boolean reachable;
    private String  errorType;
    private String  errorMessage;
    private String  collectedAt;

    public String getDsnAlias() { return dsnAlias; }
    public void setDsnAlias(String v) { this.dsnAlias = v; }
    public String getDbEngine() { return dbEngine; }
    public void setDbEngine(String v) { this.dbEngine = v; }
    public String getDbHost() { return dbHost; }
    public void setDbHost(String v) { this.dbHost = v; }
    public boolean isReachable() { return reachable; }
    public void setReachable(boolean v) { this.reachable = v; }
    public String getErrorType() { return errorType; }
    public void setErrorType(String v) { this.errorType = v; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String v) { this.errorMessage = v; }
    public String getCollectedAt() { return collectedAt; }
    public void setCollectedAt(String v) { this.collectedAt = v; }
}
