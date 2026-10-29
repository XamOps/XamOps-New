package com.xammer.cloud.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * An offboarding tombstone (design §2.1, table {@code cluster_offboarding_tombstone}).
 *
 * <p>Blocks a stale agent from silently re-registering a cluster that was
 * deliberately offboarded (FR-8.2). Keyed by {@code (cloud_account_pk,
 * cluster_name)} with a unique index. Onboarding pre-registration clears the
 * tombstone on purpose.</p>
 */
@Entity
@Table(name = "cluster_offboarding_tombstone")
public class ClusterOffboardingTombstone {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cloud_account_pk", nullable = false)
    private Long cloudAccountPk;

    @Column(name = "cluster_name", nullable = false, length = 255)
    private String clusterName;

    @Column(name = "offboarded_at", nullable = false)
    private Instant offboardedAt;

    @Column(name = "offboarded_by")
    private Long offboardedBy;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getCloudAccountPk() { return cloudAccountPk; }
    public void setCloudAccountPk(Long cloudAccountPk) { this.cloudAccountPk = cloudAccountPk; }

    public String getClusterName() { return clusterName; }
    public void setClusterName(String clusterName) { this.clusterName = clusterName; }

    public Instant getOffboardedAt() { return offboardedAt; }
    public void setOffboardedAt(Instant offboardedAt) { this.offboardedAt = offboardedAt; }

    public Long getOffboardedBy() { return offboardedBy; }
    public void setOffboardedBy(Long offboardedBy) { this.offboardedBy = offboardedBy; }
}
