package com.xammer.cloud.domain;

import javax.persistence.*;
import java.time.LocalDateTime;

/**
 * Entity for storing data pushed by XamOps agents.
 * Each record stores a specific data type (trivy, kubescape, opencost, workloads)
 * for a specific cluster.
 *
 * This table acts as a staging area for agent-pushed data, which is then
 * transformed and merged into the EksDashboardCache for UI consumption.
 */
@Entity
@Table(name = "agent_data_cache",
    uniqueConstraints = {
        @UniqueConstraint(columnNames = {"cluster_config_id", "data_type"})
    },
    indexes = {
        @Index(name = "idx_agent_data_cluster", columnList = "cluster_config_id"),
        @Index(name = "idx_agent_data_type", columnList = "data_type"),
        @Index(name = "idx_agent_data_collected", columnList = "collected_at")
    }
)
public class AgentDataCache {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cluster_config_id", nullable = false)
    private Long clusterConfigId;

    /**
     * Type of data stored:
     * - trivy-vulnerabilities
     * - kubescape-compliance
     * - opencost-allocation
     * - cluster-workloads
     * - falco-events
     */
    @Column(name = "data_type", nullable = false, length = 50)
    private String dataType;

    /**
     * Raw JSON data pushed by agent.
     * Structure depends on data_type.
     */
    @Column(name = "data_json", columnDefinition = "TEXT")
    private String dataJson;

    /**
     * Number of items in the batch
     */
    @Column(name = "item_count")
    private Integer itemCount;

    /**
     * Agent version that pushed this data
     */
    @Column(name = "agent_version", length = 20)
    private String agentVersion;

    /**
     * When the agent collected this data
     */
    @Column(name = "collected_at", nullable = false)
    private LocalDateTime collectedAt;

    /**
     * When this record was created/updated in the database
     */
    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt;

    // Constructors
    public AgentDataCache() {
        this.receivedAt = LocalDateTime.now();
    }

    public AgentDataCache(Long clusterConfigId, String dataType, String dataJson, Integer itemCount) {
        this();
        this.clusterConfigId = clusterConfigId;
        this.dataType = dataType;
        this.dataJson = dataJson;
        this.itemCount = itemCount;
        this.collectedAt = LocalDateTime.now();
    }

    @PrePersist
    protected void onCreate() {
        receivedAt = LocalDateTime.now();
        if (collectedAt == null) {
            collectedAt = LocalDateTime.now();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        receivedAt = LocalDateTime.now();
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getClusterConfigId() {
        return clusterConfigId;
    }

    public void setClusterConfigId(Long clusterConfigId) {
        this.clusterConfigId = clusterConfigId;
    }

    public String getDataType() {
        return dataType;
    }

    public void setDataType(String dataType) {
        this.dataType = dataType;
    }

    public String getDataJson() {
        return dataJson;
    }

    public void setDataJson(String dataJson) {
        this.dataJson = dataJson;
    }

    public Integer getItemCount() {
        return itemCount;
    }

    public void setItemCount(Integer itemCount) {
        this.itemCount = itemCount;
    }

    public String getAgentVersion() {
        return agentVersion;
    }

    public void setAgentVersion(String agentVersion) {
        this.agentVersion = agentVersion;
    }

    public LocalDateTime getCollectedAt() {
        return collectedAt;
    }

    public void setCollectedAt(LocalDateTime collectedAt) {
        this.collectedAt = collectedAt;
    }

    public LocalDateTime getReceivedAt() {
        return receivedAt;
    }

    public void setReceivedAt(LocalDateTime receivedAt) {
        this.receivedAt = receivedAt;
    }
}
