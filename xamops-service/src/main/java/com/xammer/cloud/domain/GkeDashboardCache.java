package com.xammer.cloud.domain;

import javax.persistence.*;
import java.time.LocalDateTime;

/**
 * Entity for storing GKE dashboard data in the database.
 * Mirrors EksDashboardCache — enables instant page loads from DB
 * without hitting the GCP API or Prometheus on every request.
 */
@Entity
@Table(name = "gke_dashboard_cache", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"cluster_name", "project_id", "location"})
})
public class GkeDashboardCache {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cluster_name", nullable = false)
    private String clusterName;

    /** GCP project ID (e.g. "my-gcp-project") */
    @Column(name = "project_id", nullable = false)
    private String projectId;

    /** Zone or region (e.g. "us-central1-a" or "us-central1") */
    @Column(name = "location", nullable = false, length = 100)
    private String location;

    @Column(name = "dashboard_data", columnDefinition = "TEXT")
    private String dashboardData;

    @Column(name = "last_synced_at", nullable = false)
    private LocalDateTime lastSyncedAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public GkeDashboardCache() {
        this.createdAt = LocalDateTime.now();
        this.lastSyncedAt = LocalDateTime.now();
    }

    public GkeDashboardCache(String clusterName, String projectId, String location, String dashboardData) {
        this();
        this.clusterName = clusterName;
        this.projectId = projectId;
        this.location = location;
        this.dashboardData = dashboardData;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getClusterName() { return clusterName; }
    public void setClusterName(String clusterName) { this.clusterName = clusterName; }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }

    public String getDashboardData() { return dashboardData; }
    public void setDashboardData(String dashboardData) { this.dashboardData = dashboardData; }

    public LocalDateTime getLastSyncedAt() { return lastSyncedAt; }
    public void setLastSyncedAt(LocalDateTime lastSyncedAt) { this.lastSyncedAt = lastSyncedAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
