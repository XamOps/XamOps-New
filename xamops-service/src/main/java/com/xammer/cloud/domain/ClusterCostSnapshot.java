package com.xammer.cloud.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One durable point-in-time record of a cluster's estimated monthly cost — the
 * prior-period baseline every trend comparison reads back against.
 *
 * <h2>Canonical identifier</h2>
 *
 * Scoped by {@code clusterConfigId} ({@code kubernetes_cluster_config.id}), the
 * same unambiguous surrogate key {@link WorkloadVerticalAction} uses. There is
 * deliberately <strong>no {@code cloud_account_id} column</strong>: that column
 * on {@code kubernetes_cluster_config} means the {@code cloud_account} primary
 * key on modern rows and the 12-digit AWS account number on legacy ones (audit
 * finding B4), so scoping through it would depend on the row's age.
 * {@code kubernetes_cluster_config.id} is unambiguous by construction. The
 * column is a PLAIN {@code Long} logical FK — there is intentionally no
 * {@code @ManyToOne}, matching the repo convention.
 *
 * <h2>Column types match what Hibernate would create</h2>
 *
 * UAT runs {@code spring.jpa.hibernate.ddl-auto=update} against the shared
 * {@code xamops_db_uat}, so Hibernate may create this table before Flyway sees
 * it. Every column type here is the one Hibernate maps the Java field to —
 * {@code BIGINT} for the ids, {@code DOUBLE PRECISION} for the primitive
 * {@code double} cost, and {@code TIMESTAMPTZ} for the {@link Instant} — so
 * whichever of Flyway or Hibernate runs first the other has nothing to alter.
 */
@Entity
@Table(name = "cluster_cost_snapshot")
public class ClusterCostSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The registered cluster this snapshot belongs to ({@code kubernetes_cluster_config.id}). */
    @Column(name = "cluster_config_id", nullable = false)
    private Long clusterConfigId;

    /** The estimated monthly cost of the cluster at the captured instant. */
    @Column(name = "cluster_monthly_cost", nullable = false)
    private double clusterMonthlyCost;

    /** When this snapshot was taken; the key the prior-period read-back orders by. */
    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    public ClusterCostSnapshot() {
    }

    public ClusterCostSnapshot(Long clusterConfigId, double clusterMonthlyCost, Instant capturedAt) {
        this.clusterConfigId = clusterConfigId;
        this.clusterMonthlyCost = clusterMonthlyCost;
        this.capturedAt = capturedAt;
    }

    @PrePersist
    protected void onCreate() {
        if (capturedAt == null) {
            capturedAt = Instant.now();
        }
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

    public double getClusterMonthlyCost() {
        return clusterMonthlyCost;
    }

    public void setClusterMonthlyCost(double clusterMonthlyCost) {
        this.clusterMonthlyCost = clusterMonthlyCost;
    }

    public Instant getCapturedAt() {
        return capturedAt;
    }

    public void setCapturedAt(Instant capturedAt) {
        this.capturedAt = capturedAt;
    }
}
