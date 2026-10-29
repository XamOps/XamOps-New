package com.xammer.cloud.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Tracks DB-only agents running on EC2 (not in Kubernetes).
 * These agents collect database metrics and push them to the server.
 * Completely separate from KubernetesClusterConfig.
 */
@Entity
@Table(name = "db_agent_config",
    uniqueConstraints = {
        @UniqueConstraint(columnNames = {"cloud_account_id", "agent_name"})
    },
    indexes = {
        @Index(name = "idx_db_agent_account", columnList = "cloud_account_id"),
        @Index(name = "idx_db_agent_status",  columnList = "status")
    }
)
public class DbAgentConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** FK to CloudAccount.id */
    @Column(name = "cloud_account_id", nullable = false)
    private Long cloudAccountId;

    /** Value of CLUSTER_NAME env var — user-defined label for this agent */
    @Column(name = "agent_name", nullable = false, length = 100)
    private String agentName;

    /** pending | active | inactive */
    @Column(name = "status", length = 20)
    private String status = "pending";

    @Column(name = "agent_version", length = 20)
    private String agentVersion;

    @Column(name = "agent_ip", length = 45)
    private String agentIp;

    @Column(name = "last_checkin")
    private LocalDateTime lastCheckin;

    // ── Connection configuration (saved from wizard) ──────────────────────────
    @Column(name = "db_engine", length = 20)
    private String dbEngine; // mysql | postgres

    @Column(name = "db_host", length = 255)
    private String dbHost;

    @Column(name = "db_port", length = 10)
    private String dbPort;

    @Column(name = "db_name", length = 100)
    private String dbName;

    @Column(name = "db_user", length = 100)
    private String dbUser;

    // SEC-5: never serialised out. The server does not use this value (the agent reads its DSN
    // from its own environment); it is no longer written and existing rows are nulled by
    // migration V20261029_1. @JsonIgnore is defence-in-depth against a future serialization path.
    @com.fasterxml.jackson.annotation.JsonIgnore
    @Column(name = "db_password", length = 255)
    private String dbPassword;

    @Column(name = "server_url", length = 255)
    private String serverUrl;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public DbAgentConfig() {}

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public boolean isActive() {
        return "active".equals(status) &&
               lastCheckin != null &&
               lastCheckin.isAfter(LocalDateTime.now().minusMinutes(10));
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getCloudAccountId() { return cloudAccountId; }
    public void setCloudAccountId(Long cloudAccountId) { this.cloudAccountId = cloudAccountId; }

    public String getAgentName() { return agentName; }
    public void setAgentName(String agentName) { this.agentName = agentName; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getAgentVersion() { return agentVersion; }
    public void setAgentVersion(String agentVersion) { this.agentVersion = agentVersion; }

    public String getAgentIp() { return agentIp; }
    public void setAgentIp(String agentIp) { this.agentIp = agentIp; }

    public LocalDateTime getLastCheckin() { return lastCheckin; }
    public void setLastCheckin(LocalDateTime lastCheckin) { this.lastCheckin = lastCheckin; }

    public String getDbEngine() { return dbEngine; }
    public void setDbEngine(String dbEngine) { this.dbEngine = dbEngine; }

    public String getDbHost() { return dbHost; }
    public void setDbHost(String dbHost) { this.dbHost = dbHost; }

    public String getDbPort() { return dbPort; }
    public void setDbPort(String dbPort) { this.dbPort = dbPort; }

    public String getDbName() { return dbName; }
    public void setDbName(String dbName) { this.dbName = dbName; }

    public String getDbUser() { return dbUser; }
    public void setDbUser(String dbUser) { this.dbUser = dbUser; }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public String getDbPassword() { return dbPassword; }
    public void setDbPassword(String dbPassword) { this.dbPassword = dbPassword; }

    public String getServerUrl() { return serverUrl; }
    public void setServerUrl(String serverUrl) { this.serverUrl = serverUrl; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
