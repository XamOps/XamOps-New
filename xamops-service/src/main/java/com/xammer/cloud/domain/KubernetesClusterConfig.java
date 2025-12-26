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

    @Column(name = "kubeconfig_yaml", columnDefinition = "TEXT", nullable = false)
    private String kubeconfigYaml;

    @Column(name = "kubescape_enabled")
    private Boolean kubescapeEnabled = false;

    @Column(name = "karpenter_enabled")
    private Boolean karpenterEnabled = false;

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
}