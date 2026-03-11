package com.xammer.cloud.domain;

import javax.persistence.*;
import java.time.LocalDateTime;

/**
 * Entity for storing AKS dashboard data in the database.
 * Mirrors EksDashboardCache — enables instant page loads from DB
 * without hitting the Azure SDK or Prometheus on every request.
 */
@Entity
@Table(name = "aks_dashboard_cache", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"cluster_name", "subscription_id", "resource_group"})
})
public class AksDashboardCache {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cluster_name", nullable = false)
    private String clusterName;

    @Column(name = "subscription_id", nullable = false, length = 100)
    private String subscriptionId;

    @Column(name = "resource_group", nullable = false)
    private String resourceGroup;

    @Column(name = "dashboard_data", columnDefinition = "TEXT")
    private String dashboardData;

    @Column(name = "last_synced_at", nullable = false)
    private LocalDateTime lastSyncedAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public AksDashboardCache() {
        this.createdAt = LocalDateTime.now();
        this.lastSyncedAt = LocalDateTime.now();
    }

    public AksDashboardCache(String clusterName, String subscriptionId, String resourceGroup, String dashboardData) {
        this();
        this.clusterName = clusterName;
        this.subscriptionId = subscriptionId;
        this.resourceGroup = resourceGroup;
        this.dashboardData = dashboardData;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getClusterName() { return clusterName; }
    public void setClusterName(String clusterName) { this.clusterName = clusterName; }

    public String getSubscriptionId() { return subscriptionId; }
    public void setSubscriptionId(String subscriptionId) { this.subscriptionId = subscriptionId; }

    public String getResourceGroup() { return resourceGroup; }
    public void setResourceGroup(String resourceGroup) { this.resourceGroup = resourceGroup; }

    public String getDashboardData() { return dashboardData; }
    public void setDashboardData(String dashboardData) { this.dashboardData = dashboardData; }

    public LocalDateTime getLastSyncedAt() { return lastSyncedAt; }
    public void setLastSyncedAt(LocalDateTime lastSyncedAt) { this.lastSyncedAt = lastSyncedAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
