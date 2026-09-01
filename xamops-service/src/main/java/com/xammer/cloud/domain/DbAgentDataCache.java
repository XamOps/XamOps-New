package com.xammer.cloud.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Stores data batches pushed by DB agents.
 * One row per (db_agent, data_type) — upserted on each push.
 */
@Entity
@Table(name = "db_agent_data_cache",
    uniqueConstraints = {
        @UniqueConstraint(columnNames = {"db_agent_id", "data_type"})
    },
    indexes = {
        @Index(name = "idx_db_agent_data_agent",     columnList = "db_agent_id"),
        @Index(name = "idx_db_agent_data_type",      columnList = "data_type"),
        @Index(name = "idx_db_agent_data_collected", columnList = "collected_at")
    }
)
public class DbAgentDataCache {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "db_agent_id", nullable = false)
    private Long dbAgentId;

    /** e.g. "db-metrics", "slow-queries", "db-connections" */
    @Column(name = "data_type", nullable = false, length = 50)
    private String dataType;

    @Column(name = "data_json", columnDefinition = "TEXT")
    private String dataJson;

    @Column(name = "item_count")
    private Integer itemCount;

    @Column(name = "agent_version", length = 20)
    private String agentVersion;

    @Column(name = "collected_at", nullable = false)
    private LocalDateTime collectedAt;

    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt;

    public DbAgentDataCache() {
        this.receivedAt = LocalDateTime.now();
    }

    @PrePersist
    protected void onCreate() {
        receivedAt = LocalDateTime.now();
        if (collectedAt == null) collectedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        receivedAt = LocalDateTime.now();
    }

    // Getters and Setters
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

    public String getAgentVersion() { return agentVersion; }
    public void setAgentVersion(String agentVersion) { this.agentVersion = agentVersion; }

    public LocalDateTime getCollectedAt() { return collectedAt; }
    public void setCollectedAt(LocalDateTime collectedAt) { this.collectedAt = collectedAt; }

    public LocalDateTime getReceivedAt() { return receivedAt; }
    public void setReceivedAt(LocalDateTime receivedAt) { this.receivedAt = receivedAt; }
}
