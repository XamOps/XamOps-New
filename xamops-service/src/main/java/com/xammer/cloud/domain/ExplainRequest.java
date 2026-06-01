package com.xammer.cloud.domain;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "explain_request")
public class ExplainRequest {

    @Id
    private String id;

    @Column(name = "cloud_account_id", nullable = false)
    private Long cloudAccountId;

    @Column(name = "agent_name", nullable = false)
    private String agentName;

    @Column(name = "dsn_alias", nullable = false)
    private String dsnAlias;

    @Column(name = "query_text", nullable = false, columnDefinition = "TEXT")
    private String queryText;

    @Column(name = "db_engine", nullable = false)
    private String dbEngine;

    @Column(nullable = false)
    private String status = "PENDING";

    @Column(name = "result_json", columnDefinition = "TEXT")
    private String resultJson;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public Long getCloudAccountId() { return cloudAccountId; }
    public void setCloudAccountId(Long v) { this.cloudAccountId = v; }
    public String getAgentName() { return agentName; }
    public void setAgentName(String v) { this.agentName = v; }
    public String getDsnAlias() { return dsnAlias; }
    public void setDsnAlias(String v) { this.dsnAlias = v; }
    public String getQueryText() { return queryText; }
    public void setQueryText(String v) { this.queryText = v; }
    public String getDbEngine() { return dbEngine; }
    public void setDbEngine(String v) { this.dbEngine = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getResultJson() { return resultJson; }
    public void setResultJson(String v) { this.resultJson = v; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String v) { this.errorMessage = v; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime v) { this.createdAt = v; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime v) { this.completedAt = v; }
}
