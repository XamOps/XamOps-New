package com.xammer.cloud.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.time.LocalDateTime;

/**
 * One workload's vertical-rightsizing policy.
 *
 * <h2>Canonical identifiers</h2>
 *
 * {@code clusterConfigId} is {@code kubernetes_cluster_config.id} and {@code clientId} is
 * {@code client.id}. There is deliberately <strong>no {@code cloud_account_id} column</strong>:
 * that column on {@code kubernetes_cluster_config} holds the {@code cloud_account} primary key on
 * modern rows and the 12-digit AWS account number on legacy ones, so a table copying it would
 * inherit a field whose meaning depends on the row's age, and tenant scoping would become a guess.
 * {@code client_id} is denormalised on purpose so scoping never traverses the ambiguous column; it
 * is resolved once at write time from the entitled {@code CloudAccount.getClient().getId()}.
 *
 * <h2>Absence means defaults</h2>
 *
 * No row means {@code WorkloadPolicyDefaults} applies, so there is no backfill and a cluster
 * registered tomorrow behaves like one registered last year. The column defaults here and the Java
 * constants must agree; {@code WorkloadPolicyServiceTest} asserts they do.
 */
@Entity
@Table(name = "vertical_workload_policy",
        uniqueConstraints = @UniqueConstraint(name = "uq_vertical_workload_policy_workload",
                columnNames = {"cluster_config_id", "namespace", "workload_kind", "workload_name"}))
public class WorkloadVerticalPolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_id", nullable = false)
    private Long clientId;

    @Column(name = "cluster_config_id", nullable = false)
    private Long clusterConfigId;

    @Column(name = "namespace", nullable = false, length = 253)
    private String namespace;

    @Column(name = "workload_kind", nullable = false, length = 32)
    private String workloadKind;

    @Column(name = "workload_name", nullable = false, length = 253)
    private String workloadName;

    /** {@code OFF | RECOMMEND | AUTO}. Stored as text so a new value does not need a migration. */
    @Column(name = "mode", nullable = false, length = 16)
    private String mode = "OFF";

    @Column(name = "headroom_pct", nullable = false)
    private Integer headroomPct = 25;

    @Column(name = "percentile", nullable = false)
    private Integer percentile = 95;

    @Column(name = "window_hours", nullable = false)
    private Integer windowHours = 24;

    @Column(name = "cpu_floor_millicores", nullable = false)
    private Integer cpuFloorMillicores = 25;

    @Column(name = "memory_floor_bytes", nullable = false)
    private Long memoryFloorBytes = 262144000L;

    @Column(name = "min_increase_pct", nullable = false)
    private Integer minIncreasePct = 15;

    @Column(name = "min_decrease_pct", nullable = false)
    private Integer minDecreasePct = 30;

    @Column(name = "min_increase_cpu_millicores", nullable = false)
    private Integer minIncreaseCpuMillicores = 25;

    @Column(name = "min_decrease_cpu_millicores", nullable = false)
    private Integer minDecreaseCpuMillicores = 100;

    @Column(name = "min_increase_memory_bytes", nullable = false)
    private Long minIncreaseMemoryBytes = 33554432L;

    @Column(name = "min_decrease_memory_bytes", nullable = false)
    private Long minDecreaseMemoryBytes = 134217728L;

    /** {@code IN_PLACE | IN_PLACE_AND_PERSIST}. */
    @Column(name = "apply_method", nullable = false, length = 32)
    private String applyMethod = "IN_PLACE";

    @Column(name = "cooldown_minutes", nullable = false)
    private Integer cooldownMinutes = 60;

    @Column(name = "max_limit_increase_factor", nullable = false)
    private Double maxLimitIncreaseFactor = 4.0;

    @Column(name = "protect_single_replica", nullable = false)
    private Boolean protectSingleReplica = Boolean.TRUE;

    @Column(name = "node_capacity_fraction", nullable = false)
    private Double nodeCapacityFraction = 0.9;

    /** When a change was last applied to this workload; drives the cooldown. */
    @Column(name = "last_applied_at")
    private Instant lastAppliedAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getClientId() {
        return clientId;
    }

    public void setClientId(Long clientId) {
        this.clientId = clientId;
    }

    public Long getClusterConfigId() {
        return clusterConfigId;
    }

    public void setClusterConfigId(Long clusterConfigId) {
        this.clusterConfigId = clusterConfigId;
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public String getWorkloadKind() {
        return workloadKind;
    }

    public void setWorkloadKind(String workloadKind) {
        this.workloadKind = workloadKind;
    }

    public String getWorkloadName() {
        return workloadName;
    }

    public void setWorkloadName(String workloadName) {
        this.workloadName = workloadName;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public Integer getHeadroomPct() {
        return headroomPct;
    }

    public void setHeadroomPct(Integer headroomPct) {
        this.headroomPct = headroomPct;
    }

    public Integer getPercentile() {
        return percentile;
    }

    public void setPercentile(Integer percentile) {
        this.percentile = percentile;
    }

    public Integer getWindowHours() {
        return windowHours;
    }

    public void setWindowHours(Integer windowHours) {
        this.windowHours = windowHours;
    }

    public Integer getCpuFloorMillicores() {
        return cpuFloorMillicores;
    }

    public void setCpuFloorMillicores(Integer cpuFloorMillicores) {
        this.cpuFloorMillicores = cpuFloorMillicores;
    }

    public Long getMemoryFloorBytes() {
        return memoryFloorBytes;
    }

    public void setMemoryFloorBytes(Long memoryFloorBytes) {
        this.memoryFloorBytes = memoryFloorBytes;
    }

    public Integer getMinIncreasePct() {
        return minIncreasePct;
    }

    public void setMinIncreasePct(Integer minIncreasePct) {
        this.minIncreasePct = minIncreasePct;
    }

    public Integer getMinDecreasePct() {
        return minDecreasePct;
    }

    public void setMinDecreasePct(Integer minDecreasePct) {
        this.minDecreasePct = minDecreasePct;
    }

    public Integer getMinIncreaseCpuMillicores() {
        return minIncreaseCpuMillicores;
    }

    public void setMinIncreaseCpuMillicores(Integer minIncreaseCpuMillicores) {
        this.minIncreaseCpuMillicores = minIncreaseCpuMillicores;
    }

    public Integer getMinDecreaseCpuMillicores() {
        return minDecreaseCpuMillicores;
    }

    public void setMinDecreaseCpuMillicores(Integer minDecreaseCpuMillicores) {
        this.minDecreaseCpuMillicores = minDecreaseCpuMillicores;
    }

    public Long getMinIncreaseMemoryBytes() {
        return minIncreaseMemoryBytes;
    }

    public void setMinIncreaseMemoryBytes(Long minIncreaseMemoryBytes) {
        this.minIncreaseMemoryBytes = minIncreaseMemoryBytes;
    }

    public Long getMinDecreaseMemoryBytes() {
        return minDecreaseMemoryBytes;
    }

    public void setMinDecreaseMemoryBytes(Long minDecreaseMemoryBytes) {
        this.minDecreaseMemoryBytes = minDecreaseMemoryBytes;
    }

    public String getApplyMethod() {
        return applyMethod;
    }

    public void setApplyMethod(String applyMethod) {
        this.applyMethod = applyMethod;
    }

    public Integer getCooldownMinutes() {
        return cooldownMinutes;
    }

    public void setCooldownMinutes(Integer cooldownMinutes) {
        this.cooldownMinutes = cooldownMinutes;
    }

    public Double getMaxLimitIncreaseFactor() {
        return maxLimitIncreaseFactor;
    }

    public void setMaxLimitIncreaseFactor(Double maxLimitIncreaseFactor) {
        this.maxLimitIncreaseFactor = maxLimitIncreaseFactor;
    }

    public Boolean getProtectSingleReplica() {
        return protectSingleReplica;
    }

    public void setProtectSingleReplica(Boolean protectSingleReplica) {
        this.protectSingleReplica = protectSingleReplica;
    }

    public Double getNodeCapacityFraction() {
        return nodeCapacityFraction;
    }

    public void setNodeCapacityFraction(Double nodeCapacityFraction) {
        this.nodeCapacityFraction = nodeCapacityFraction;
    }

    public Instant getLastAppliedAt() {
        return lastAppliedAt;
    }

    public void setLastAppliedAt(Instant lastAppliedAt) {
        this.lastAppliedAt = lastAppliedAt;
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
