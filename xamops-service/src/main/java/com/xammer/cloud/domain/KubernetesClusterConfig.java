package com.xammer.cloud.domain;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "kubernetes_cluster_config")
public class KubernetesClusterConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cloud_account_id", nullable = false)
    private Long cloudAccountId;

    @Column(name = "cluster_name", nullable = false)
    private String clusterName;

    @Column(name = "region", nullable = false)
    private String region;

    @Column(name = "k8s_version")
    private String k8sVersion;

    @Column(name = "kubeconfig_yaml", columnDefinition = "TEXT")
    private String kubeconfigYaml;

    // ===== XamOps Agent Fields =====
    @Column(name = "agent_api_key", length = 64, unique = true)
    private String agentApiKey;

    @Column(name = "agent_version", length = 20)
    private String agentVersion;

    @Column(name = "last_agent_checkin")
    private LocalDateTime lastAgentCheckin;

    @Column(name = "agent_status", length = 20)
    private String agentStatus = "pending"; // pending | active | inactive | error

    @Column(name = "data_source_mode", length = 20)
    private String dataSourceMode = "agent"; // agent | kubeconfig | hybrid (agent is default for both public/private clusters)

    @Column(name = "kubescape_enabled")
    private Boolean kubescapeEnabled = false;

    @Column(name = "karpenter_enabled")
    private Boolean karpenterEnabled = false;

    @Column(name = "opencost_enabled")
    private Boolean opencostEnabled = false;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getCloudAccountId() {
        return cloudAccountId;
    }

    public void setCloudAccountId(Long cloudAccountId) {
        this.cloudAccountId = cloudAccountId;
    }

    public String getClusterName() {
        return clusterName;
    }

    public void setClusterName(String clusterName) {
        this.clusterName = clusterName;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getK8sVersion() {
        return k8sVersion;
    }

    public void setK8sVersion(String k8sVersion) {
        this.k8sVersion = k8sVersion;
    }

    public String getKubeconfigYaml() {
        return kubeconfigYaml;
    }

    public void setKubeconfigYaml(String kubeconfigYaml) {
        this.kubeconfigYaml = kubeconfigYaml;
    }

    public Boolean getKubescapeEnabled() {
        return kubescapeEnabled;
    }

    public void setKubescapeEnabled(Boolean kubescapeEnabled) {
        this.kubescapeEnabled = kubescapeEnabled;
    }

    public Boolean getKarpenterEnabled() {
        return karpenterEnabled;
    }

    public void setKarpenterEnabled(Boolean karpenterEnabled) {
        this.karpenterEnabled = karpenterEnabled;
    }

    public Boolean getOpencostEnabled() {
        return opencostEnabled;
    }

    public void setOpencostEnabled(Boolean opencostEnabled) {
        this.opencostEnabled = opencostEnabled;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    // ===== Agent Field Getters and Setters =====
    public String getAgentApiKey() {
        return agentApiKey;
    }

    public void setAgentApiKey(String agentApiKey) {
        this.agentApiKey = agentApiKey;
    }

    public String getAgentVersion() {
        return agentVersion;
    }

    public void setAgentVersion(String agentVersion) {
        this.agentVersion = agentVersion;
    }

    public LocalDateTime getLastAgentCheckin() {
        return lastAgentCheckin;
    }

    public void setLastAgentCheckin(LocalDateTime lastAgentCheckin) {
        this.lastAgentCheckin = lastAgentCheckin;
    }

    public String getAgentStatus() {
        return agentStatus;
    }

    public void setAgentStatus(String agentStatus) {
        this.agentStatus = agentStatus;
    }

    public String getDataSourceMode() {
        return dataSourceMode;
    }

    public void setDataSourceMode(String dataSourceMode) {
        this.dataSourceMode = dataSourceMode;
    }

    /**
     * Check if cluster uses agent-based data collection
     */
    public boolean isAgentEnabled() {
        return "agent".equals(dataSourceMode) || "hybrid".equals(dataSourceMode);
    }

    /**
     * Check if cluster uses kubeconfig-based data collection
     */
    public boolean isKubeconfigEnabled() {
        return "kubeconfig".equals(dataSourceMode) || "hybrid".equals(dataSourceMode);
    }

    /**
     * Check if agent is currently active (checked in within last 15 minutes)
     */
    public boolean isAgentActive() {
        if (lastAgentCheckin == null) return false;
        return lastAgentCheckin.isAfter(LocalDateTime.now().minusMinutes(15));
    }
}