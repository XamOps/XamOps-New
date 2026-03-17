package com.xammer.cloud.domain;

import javax.persistence.*;
import java.time.LocalDateTime;

/**
 * Stores per-push snapshots of db-instance-metrics from DB agents.
 * One row per push (appended, not upserted) — enables time-series charts.
 * Retained for 30 days; older rows are pruned by a scheduled job.
 */
@Entity
@Table(name = "db_agent_metrics_history",
    indexes = {
        @Index(name = "idx_db_metrics_hist_agent",     columnList = "db_agent_id"),
        @Index(name = "idx_db_metrics_hist_type",      columnList = "data_type"),
        @Index(name = "idx_db_metrics_hist_collected", columnList = "collected_at")
    }
)
public class DbAgentMetricsHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "db_agent_id", nullable = false)
    private Long dbAgentId;

    /** "db-instance-metrics" */
    @Column(name = "data_type", nullable = false, length = 50)
    private String dataType;

    @Column(name = "data_json", columnDefinition = "TEXT")
    private String dataJson;

    @Column(name = "item_count")
    private Integer itemCount;

    @Column(name = "collected_at", nullable = false)
    private LocalDateTime collectedAt;

    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt;

    public DbAgentMetricsHistory() {
        this.receivedAt = LocalDateTime.now();
    }

    @PrePersist
    protected void onCreate() {
        receivedAt = LocalDateTime.now();
        if (collectedAt == null) collectedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getDbAgentId() { return dbAgentId; }
    public void setDbAgentId(Long dbAgentId) { this.dbAgentId = dbAgentId; }
    public String getDataType() { return dataType; }
    public void setDataType(String dataType) { this.dataType = dataType; }
    public String getDataJson() { return dataJson; }
    public void setDataJson(String dataJson) { this.dataJson = dataJson; }
    public Integer getItemCount() { return itemCount; }
    public void setItemCount(Integer itemCount) { this.itemCount = itemCount; }
    public LocalDateTime getCollectedAt() { return collectedAt; }
    public void setCollectedAt(LocalDateTime collectedAt) { this.collectedAt = collectedAt; }
    public LocalDateTime getReceivedAt() { return receivedAt; }
    public void setReceivedAt(LocalDateTime receivedAt) { this.receivedAt = receivedAt; }
}
