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

/**
 * One interrupted Spot node lifecycle: the notices XamOps observed for it, its removal, the
 * workloads on it at notice, the XamOps drain outcome and how long those workloads took to be
 * Ready again. Written only by {@code SpotInterruptionTracker} from data producers actually
 * reported; every unknown value is {@code null}.
 *
 * <h2>Canonical identifier</h2>
 *
 * Scoped by {@code clusterConfigId} ({@code kubernetes_cluster_config.id}), the same
 * unambiguous surrogate key {@link ClusterCostSnapshot} uses. There is deliberately
 * <strong>no {@code cloud_account_id} column</strong>: that column on
 * {@code kubernetes_cluster_config} means the {@code cloud_account} primary key on modern rows
 * and the 12-digit AWS account number on legacy ones (audit finding B4). The column is a PLAIN
 * {@code Long} logical FK — there is intentionally no {@code @ManyToOne}, matching the repo
 * convention. The key {@code (cluster_config_id, node_name, node_created_at)} is one node
 * lifecycle: EKS node names are IP-derived and reused, the creation time tells them apart.
 *
 * <h2>Column types match what Hibernate would create</h2>
 *
 * UAT runs {@code spring.jpa.hibernate.ddl-auto=update} against the shared
 * {@code xamops_db_uat}, so Hibernate may create this table before Flyway sees it
 * ({@code V20261024_38__k8s_spot_interruption_event.sql}). {@code BIGINT} ids,
 * {@code TIMESTAMPTZ} for every {@link Instant}, {@code VARCHAR(n)} equal to each
 * {@code length}, nullable {@code Boolean}/{@code Integer} wrappers and {@code TEXT} via
 * {@code columnDefinition} — so whichever of Flyway or Hibernate runs first the other has
 * nothing to alter ({@code SchemaUpdateNoAlterPgTest}).
 */
@Entity
@Table(name = "k8s_spot_interruption_event",
        uniqueConstraints = @UniqueConstraint(name = "uq_k8s_spot_interruption_event_node",
                columnNames = {"cluster_config_id", "node_name", "node_created_at"}))
public class K8sSpotInterruptionEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The registered cluster ({@code kubernetes_cluster_config.id}). */
    @Column(name = "cluster_config_id", nullable = false)
    private Long clusterConfigId;

    @Column(name = "node_name", nullable = false, length = 253)
    private String nodeName;

    /** The node's creation time; with the name it identifies one node lifecycle. */
    @Column(name = "node_created_at", nullable = false)
    private Instant nodeCreatedAt;

    @Column(name = "instance_id", length = 32)
    private String instanceId;

    @Column(name = "instance_type", length = 64)
    private String instanceType;

    @Column(name = "zone", length = 32)
    private String zone;

    @Column(name = "capacity_type", length = 16)
    private String capacityType;

    @Column(name = "nodegroup", length = 255)
    private String nodegroup;

    /** interruption | rebalance | unconfirmed. */
    @Column(name = "notice_kind", nullable = false, length = 16)
    private String noticeKind;

    /** Sorted comma set of nth-taint, nth-label-legacy, karpenter-event, node-removed. */
    @Column(name = "sources", nullable = false, length = 255)
    private String sources;

    /** Earliest notice or observation of any kind. */
    @Column(name = "noticed_at", nullable = false)
    private Instant noticedAt;

    @Column(name = "rebalance_noticed_at")
    private Instant rebalanceNoticedAt;

    @Column(name = "interruption_noticed_at")
    private Instant interruptionNoticedAt;

    /** Last snapshot that still listed the node (t0); lower edge of an unconfirmed removal. */
    @Column(name = "node_last_seen_at")
    private Instant nodeLastSeenAt;

    /** First snapshot without the node (t1). */
    @Column(name = "node_removed_at")
    private Instant nodeRemovedAt;

    @Column(name = "cordoned_before_removal")
    private Boolean cordonedBeforeRemoval;

    /** live | simulate | off | not-eligible | unknown. */
    @Column(name = "handling_mode", length = 16)
    private String handlingMode;

    @Column(name = "handling_reason", length = 255)
    private String handlingReason;

    /** ActionRecord.status of the XamOps spot-interruption drain. */
    @Column(name = "drain_status", length = 16)
    private String drainStatus;

    @Column(name = "drain_reason", columnDefinition = "TEXT")
    private String drainReason;

    @Column(name = "drain_dry_run")
    private Boolean drainDryRun;

    @Column(name = "drain_finished_at")
    private Instant drainFinishedAt;

    /** JSON array of {namespace, kind, name, podsOnNode}; null = unknown, [] = observed none. */
    @Column(name = "affected_workloads", columnDefinition = "TEXT")
    private String affectedWorkloads;

    @Column(name = "pod_count")
    private Integer podCount;

    @Column(name = "workloads_snapshot_at")
    private Instant workloadsSnapshotAt;

    /** tracking | recovered | not-observed | node-not-removed | no-workloads | no-snapshot | not-measurable. */
    @Column(name = "recovery_status", nullable = false, length = 20)
    private String recoveryStatus;

    @Column(name = "recovery_last_unrecovered_at")
    private Instant recoveryLastUnrecoveredAt;

    @Column(name = "recovered_observed_at")
    private Instant recoveredObservedAt;

    @Column(name = "pending_first_seen_at")
    private Instant pendingFirstSeenAt;

    @Column(name = "pending_last_seen_at")
    private Instant pendingLastSeenAt;

    @Column(name = "pending_max_pods")
    private Integer pendingMaxPods;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public K8sSpotInterruptionEvent() {
    }

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

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

    public String getNodeName() {
        return nodeName;
    }

    public void setNodeName(String nodeName) {
        this.nodeName = nodeName;
    }

    public Instant getNodeCreatedAt() {
        return nodeCreatedAt;
    }

    public void setNodeCreatedAt(Instant nodeCreatedAt) {
        this.nodeCreatedAt = nodeCreatedAt;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(String instanceId) {
        this.instanceId = instanceId;
    }

    public String getInstanceType() {
        return instanceType;
    }

    public void setInstanceType(String instanceType) {
        this.instanceType = instanceType;
    }

    public String getZone() {
        return zone;
    }

    public void setZone(String zone) {
        this.zone = zone;
    }

    public String getCapacityType() {
        return capacityType;
    }

    public void setCapacityType(String capacityType) {
        this.capacityType = capacityType;
    }

    public String getNodegroup() {
        return nodegroup;
    }

    public void setNodegroup(String nodegroup) {
        this.nodegroup = nodegroup;
    }

    public String getNoticeKind() {
        return noticeKind;
    }

    public void setNoticeKind(String noticeKind) {
        this.noticeKind = noticeKind;
    }

    public String getSources() {
        return sources;
    }

    public void setSources(String sources) {
        this.sources = sources;
    }

    public Instant getNoticedAt() {
        return noticedAt;
    }

    public void setNoticedAt(Instant noticedAt) {
        this.noticedAt = noticedAt;
    }

    public Instant getRebalanceNoticedAt() {
        return rebalanceNoticedAt;
    }

    public void setRebalanceNoticedAt(Instant rebalanceNoticedAt) {
        this.rebalanceNoticedAt = rebalanceNoticedAt;
    }

    public Instant getInterruptionNoticedAt() {
        return interruptionNoticedAt;
    }

    public void setInterruptionNoticedAt(Instant interruptionNoticedAt) {
        this.interruptionNoticedAt = interruptionNoticedAt;
    }

    public Instant getNodeLastSeenAt() {
        return nodeLastSeenAt;
    }

    public void setNodeLastSeenAt(Instant nodeLastSeenAt) {
        this.nodeLastSeenAt = nodeLastSeenAt;
    }

    public Instant getNodeRemovedAt() {
        return nodeRemovedAt;
    }

    public void setNodeRemovedAt(Instant nodeRemovedAt) {
        this.nodeRemovedAt = nodeRemovedAt;
    }

    public Boolean getCordonedBeforeRemoval() {
        return cordonedBeforeRemoval;
    }

    public void setCordonedBeforeRemoval(Boolean cordonedBeforeRemoval) {
        this.cordonedBeforeRemoval = cordonedBeforeRemoval;
    }

    public String getHandlingMode() {
        return handlingMode;
    }

    public void setHandlingMode(String handlingMode) {
        this.handlingMode = handlingMode;
    }

    public String getHandlingReason() {
        return handlingReason;
    }

    public void setHandlingReason(String handlingReason) {
        this.handlingReason = handlingReason;
    }

    public String getDrainStatus() {
        return drainStatus;
    }

    public void setDrainStatus(String drainStatus) {
        this.drainStatus = drainStatus;
    }

    public String getDrainReason() {
        return drainReason;
    }

    public void setDrainReason(String drainReason) {
        this.drainReason = drainReason;
    }

    public Boolean getDrainDryRun() {
        return drainDryRun;
    }

    public void setDrainDryRun(Boolean drainDryRun) {
        this.drainDryRun = drainDryRun;
    }

    public Instant getDrainFinishedAt() {
        return drainFinishedAt;
    }

    public void setDrainFinishedAt(Instant drainFinishedAt) {
        this.drainFinishedAt = drainFinishedAt;
    }

    public String getAffectedWorkloads() {
        return affectedWorkloads;
    }

    public void setAffectedWorkloads(String affectedWorkloads) {
        this.affectedWorkloads = affectedWorkloads;
    }

    public Integer getPodCount() {
        return podCount;
    }

    public void setPodCount(Integer podCount) {
        this.podCount = podCount;
    }

    public Instant getWorkloadsSnapshotAt() {
        return workloadsSnapshotAt;
    }

    public void setWorkloadsSnapshotAt(Instant workloadsSnapshotAt) {
        this.workloadsSnapshotAt = workloadsSnapshotAt;
    }

    public String getRecoveryStatus() {
        return recoveryStatus;
    }

    public void setRecoveryStatus(String recoveryStatus) {
        this.recoveryStatus = recoveryStatus;
    }

    public Instant getRecoveryLastUnrecoveredAt() {
        return recoveryLastUnrecoveredAt;
    }

    public void setRecoveryLastUnrecoveredAt(Instant recoveryLastUnrecoveredAt) {
        this.recoveryLastUnrecoveredAt = recoveryLastUnrecoveredAt;
    }

    public Instant getRecoveredObservedAt() {
        return recoveredObservedAt;
    }

    public void setRecoveredObservedAt(Instant recoveredObservedAt) {
        this.recoveredObservedAt = recoveredObservedAt;
    }

    public Instant getPendingFirstSeenAt() {
        return pendingFirstSeenAt;
    }

    public void setPendingFirstSeenAt(Instant pendingFirstSeenAt) {
        this.pendingFirstSeenAt = pendingFirstSeenAt;
    }

    public Instant getPendingLastSeenAt() {
        return pendingLastSeenAt;
    }

    public void setPendingLastSeenAt(Instant pendingLastSeenAt) {
        this.pendingLastSeenAt = pendingLastSeenAt;
    }

    public Integer getPendingMaxPods() {
        return pendingMaxPods;
    }

    public void setPendingMaxPods(Integer pendingMaxPods) {
        this.pendingMaxPods = pendingMaxPods;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
