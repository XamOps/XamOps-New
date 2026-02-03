package com.xammer.cloud.domain;

import javax.persistence.*;
import java.time.LocalDateTime;

/**
 * Entity for storing EKS dashboard data in database.
 * This enables caching dashboard data to avoid fetching from Prometheus/K8s on every page load.
 */
@Entity
@Table(name = "eks_dashboard_cache", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"cluster_name", "account_id", "region"})
})
public class EksDashboardCache {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cluster_name", nullable = false)
    private String clusterName;

    @Column(name = "account_id", nullable = false, length = 50)
    private String accountId;

    @Column(name = "region", nullable = false, length = 50)
    private String region;

    @Column(name = "dashboard_data", columnDefinition = "TEXT")
    private String dashboardData;

    @Column(name = "last_synced_at", nullable = false)
    private LocalDateTime lastSyncedAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    // Constructors
    public EksDashboardCache() {
        this.createdAt = LocalDateTime.now();
        this.lastSyncedAt = LocalDateTime.now();
    }

    public EksDashboardCache(String clusterName, String accountId, String region, String dashboardData) {
        this();
        this.clusterName = clusterName;
        this.accountId = accountId;
        this.region = region;
        this.dashboardData = dashboardData;
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getClusterName() {
        return clusterName;
    }

    public void setClusterName(String clusterName) {
        this.clusterName = clusterName;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getDashboardData() {
        return dashboardData;
    }

    public void setDashboardData(String dashboardData) {
        this.dashboardData = dashboardData;
    }

    public LocalDateTime getLastSyncedAt() {
        return lastSyncedAt;
    }

    public void setLastSyncedAt(LocalDateTime lastSyncedAt) {
        this.lastSyncedAt = lastSyncedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
