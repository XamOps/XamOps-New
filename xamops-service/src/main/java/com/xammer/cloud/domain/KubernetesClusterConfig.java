package com.xammer.cloud.domain;

import jakarta.persistence.*;
import java.time.Instant;
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

    @Column(name = "autoscaling_enabled")
    private Boolean autoscalingEnabled = false;

    @Column(name = "is_private")
    private Boolean isPrivate;

    @Column(name = "cluster_type_detected_at")
    private LocalDateTime clusterTypeDetectedAt;

    // ===== CloudK8s onboarding fields (design §2.1; all additive, nullable) =====
    @Column(name = "agent_key_issued_at")
    private Instant agentKeyIssuedAt;

    @Column(name = "agent_key_last_used_at")
    private Instant agentKeyLastUsedAt;

    @Column(name = "last_agent_key_scope", length = 16)
    private String lastAgentKeyScope; // CLUSTER | ACCOUNT | LEGACY_GLOBAL

    @Column(name = "automation_agent_api_key", length = 64)
    private String automationAgentApiKey;

    @Column(name = "automation_key_issued_at")
    private Instant automationKeyIssuedAt;

    @Column(name = "automation_key_last_used_at")
    private Instant automationKeyLastUsedAt;

    @Column(name = "last_exchange_at")
    private Instant lastExchangeAt;

    @Column(name = "identity_mode", length = 16)
    private String identityMode; // POD_IDENTITY | IRSA | NONE

    @Column(name = "iam_stack_name", length = 128)
    private String iamStackName;

    @Column(name = "read_role_arn", length = 512)
    private String readRoleArn;

    @Column(name = "write_role_arn", length = 512)
    private String writeRoleArn;

    @Column(name = "node_scaler", length = 24)
    private String nodeScaler; // KARPENTER | CLUSTER_AUTOSCALER | BOTH | NONE | EKS_AUTO_MODE | UNKNOWN

    @Column(name = "node_scaler_detected_at")
    private Instant nodeScalerDetectedAt;

    @Column(name = "onboarding_features", length = 255)
    private String onboardingFeatures; // comma list of FeatureId

    @Column(name = "automation_consent_at")
    private Instant automationConsentAt;

    @Column(name = "automation_consent_by")
    private Long automationConsentBy; // users.id, no FK

    @Column(name = "automation_permission_hash", length = 64)
    private String automationPermissionHash;

    @Column(name = "automation_verified_at")
    private Instant automationVerifiedAt;

    @Column(name = "automation_identity_arn", length = 512)
    private String automationIdentityArn;

    @Column(name = "automation_identity_seen_at")
    private Instant automationIdentitySeenAt;

    @Column(name = "automation_disabled_at")
    private Instant automationDisabledAt;

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

    public Boolean getAutoscalingEnabled() {
        return autoscalingEnabled;
    }

    public void setAutoscalingEnabled(Boolean autoscalingEnabled) {
        this.autoscalingEnabled = autoscalingEnabled;
    }

    public Boolean getIsPrivate() {
        return isPrivate;
    }

    public void setIsPrivate(Boolean isPrivate) {
        this.isPrivate = isPrivate;
    }

    public LocalDateTime getClusterTypeDetectedAt() {
        return clusterTypeDetectedAt;
    }

    public void setClusterTypeDetectedAt(LocalDateTime clusterTypeDetectedAt) {
        this.clusterTypeDetectedAt = clusterTypeDetectedAt;
    }

    // ===== CloudK8s onboarding getters/setters =====
    public Instant getAgentKeyIssuedAt() { return agentKeyIssuedAt; }
    public void setAgentKeyIssuedAt(Instant agentKeyIssuedAt) { this.agentKeyIssuedAt = agentKeyIssuedAt; }

    public Instant getAgentKeyLastUsedAt() { return agentKeyLastUsedAt; }
    public void setAgentKeyLastUsedAt(Instant agentKeyLastUsedAt) { this.agentKeyLastUsedAt = agentKeyLastUsedAt; }

    public String getLastAgentKeyScope() { return lastAgentKeyScope; }
    public void setLastAgentKeyScope(String lastAgentKeyScope) { this.lastAgentKeyScope = lastAgentKeyScope; }

    public String getAutomationAgentApiKey() { return automationAgentApiKey; }
    public void setAutomationAgentApiKey(String automationAgentApiKey) { this.automationAgentApiKey = automationAgentApiKey; }

    public Instant getAutomationKeyIssuedAt() { return automationKeyIssuedAt; }
    public void setAutomationKeyIssuedAt(Instant automationKeyIssuedAt) { this.automationKeyIssuedAt = automationKeyIssuedAt; }

    public Instant getAutomationKeyLastUsedAt() { return automationKeyLastUsedAt; }
    public void setAutomationKeyLastUsedAt(Instant automationKeyLastUsedAt) { this.automationKeyLastUsedAt = automationKeyLastUsedAt; }

    public Instant getLastExchangeAt() { return lastExchangeAt; }
    public void setLastExchangeAt(Instant lastExchangeAt) { this.lastExchangeAt = lastExchangeAt; }

    public String getIdentityMode() { return identityMode; }
    public void setIdentityMode(String identityMode) { this.identityMode = identityMode; }

    public String getIamStackName() { return iamStackName; }
    public void setIamStackName(String iamStackName) { this.iamStackName = iamStackName; }

    public String getReadRoleArn() { return readRoleArn; }
    public void setReadRoleArn(String readRoleArn) { this.readRoleArn = readRoleArn; }

    public String getWriteRoleArn() { return writeRoleArn; }
    public void setWriteRoleArn(String writeRoleArn) { this.writeRoleArn = writeRoleArn; }

    public String getNodeScaler() { return nodeScaler; }
    public void setNodeScaler(String nodeScaler) { this.nodeScaler = nodeScaler; }

    public Instant getNodeScalerDetectedAt() { return nodeScalerDetectedAt; }
    public void setNodeScalerDetectedAt(Instant nodeScalerDetectedAt) { this.nodeScalerDetectedAt = nodeScalerDetectedAt; }

    public String getOnboardingFeatures() { return onboardingFeatures; }
    public void setOnboardingFeatures(String onboardingFeatures) { this.onboardingFeatures = onboardingFeatures; }

    public Instant getAutomationConsentAt() { return automationConsentAt; }
    public void setAutomationConsentAt(Instant automationConsentAt) { this.automationConsentAt = automationConsentAt; }

    public Long getAutomationConsentBy() { return automationConsentBy; }
    public void setAutomationConsentBy(Long automationConsentBy) { this.automationConsentBy = automationConsentBy; }

    public String getAutomationPermissionHash() { return automationPermissionHash; }
    public void setAutomationPermissionHash(String automationPermissionHash) { this.automationPermissionHash = automationPermissionHash; }

    public Instant getAutomationVerifiedAt() { return automationVerifiedAt; }
    public void setAutomationVerifiedAt(Instant automationVerifiedAt) { this.automationVerifiedAt = automationVerifiedAt; }

    public String getAutomationIdentityArn() { return automationIdentityArn; }
    public void setAutomationIdentityArn(String automationIdentityArn) { this.automationIdentityArn = automationIdentityArn; }

    public Instant getAutomationIdentitySeenAt() { return automationIdentitySeenAt; }
    public void setAutomationIdentitySeenAt(Instant automationIdentitySeenAt) { this.automationIdentitySeenAt = automationIdentitySeenAt; }

    public Instant getAutomationDisabledAt() { return automationDisabledAt; }
    public void setAutomationDisabledAt(Instant automationDisabledAt) { this.automationDisabledAt = automationDisabledAt; }

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