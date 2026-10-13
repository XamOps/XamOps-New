package com.xammer.cloud.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDateTime;

/**
 * One durable record of an apply the engine carried on one workload — the audit row behind every
 * change, written from the same path a human's Apply takes.
 *
 * <h2>Canonical identifiers</h2>
 *
 * Scoped by {@code clusterConfigId} ({@code kubernetes_cluster_config.id}) and {@code clientId}
 * ({@code client.id}), the same unambiguous pair {@link WorkloadVerticalPolicy} uses. There is
 * deliberately <strong>no {@code cloud_account_id} column</strong>: that column on
 * {@code kubernetes_cluster_config} means the {@code cloud_account} primary key on modern rows and
 * the 12-digit AWS account number on legacy ones, so tenant scoping through it would be a guess.
 * {@code clientId} is denormalised at write time from the entitled
 * {@code CloudAccount.getClient().getId()}.
 *
 * <h2>Trigger and outcome are text</h2>
 *
 * {@code triggerSource} is {@code AUTO | MANUAL} ({@link
 * com.xammer.cloud.service.automation.workload.ActionTrigger}) and {@code outcome} is the published
 * five-state audit vocabulary ({@code APPLIED | PARTIALLY_APPLIED | REFUSED | FAILED | EXPIRED},
 * {@code ResizeProof.publishedTerminal()}). Both are stored as text so a new value needs no
 * migration, mirroring {@code mode} / {@code apply_method} on the policy table.
 *
 * <h2>Column types match what Hibernate would create</h2>
 *
 * UAT runs {@code ddl-auto=update} against the shared {@code xamops_db_uat}, so every column type
 * here is the one Hibernate maps the Java field to — {@code TEXT} for the before/after/detail
 * strings via {@code columnDefinition}, {@code TIMESTAMPTZ} for the {@link Instant} and a plain
 * {@code TIMESTAMP} for the {@link LocalDateTime} audit stamp — leaving the migration nothing to
 * alter.
 */
@Entity
@Table(name = "vertical_workload_action")
public class WorkloadVerticalAction {

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

    /** The command's idempotency key, so a row joins back to the executor's proof. */
    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    /** {@code AUTO | MANUAL}. Stored as text so a new trigger needs no migration. */
    @Column(name = "trigger_source", nullable = false, length = 16)
    private String triggerSource;

    @Column(name = "dry_run", nullable = false)
    private Boolean dryRun = Boolean.FALSE;

    /** The published five-state audit outcome. Stored as text so a new value needs no migration. */
    @Column(name = "outcome", nullable = false, length = 32)
    private String outcome;

    /** True only when every pod touched was proved not to have restarted. */
    @Column(name = "restart_free", nullable = false)
    private Boolean restartFree = Boolean.FALSE;

    /** The submitted-target resources before the apply, serialised, so the record reads without a join. */
    @Column(name = "before_json", columnDefinition = "text")
    private String beforeJson;

    /** The observed resources after the apply, serialised. */
    @Column(name = "after_json", columnDefinition = "text")
    private String afterJson;

    /** Human-readable detail from the proof — what was done and seen. */
    @Column(name = "detail", columnDefinition = "text")
    private String detail;

    /** When the change was applied; the audit timeline's instant. */
    @Column(name = "applied_at")
    private Instant appliedAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
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

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }

    public String getTriggerSource() {
        return triggerSource;
    }

    public void setTriggerSource(String triggerSource) {
        this.triggerSource = triggerSource;
    }

    public Boolean getDryRun() {
        return dryRun;
    }

    public void setDryRun(Boolean dryRun) {
        this.dryRun = dryRun;
    }

    public String getOutcome() {
        return outcome;
    }

    public void setOutcome(String outcome) {
        this.outcome = outcome;
    }

    public Boolean getRestartFree() {
        return restartFree;
    }

    public void setRestartFree(Boolean restartFree) {
        this.restartFree = restartFree;
    }

    public String getBeforeJson() {
        return beforeJson;
    }

    public void setBeforeJson(String beforeJson) {
        this.beforeJson = beforeJson;
    }

    public String getAfterJson() {
        return afterJson;
    }

    public void setAfterJson(String afterJson) {
        this.afterJson = afterJson;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public Instant getAppliedAt() {
        return appliedAt;
    }

    public void setAppliedAt(Instant appliedAt) {
        this.appliedAt = appliedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
