package com.xammer.cloud.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * An onboarding audit event (design §2.1, table {@code cluster_onboarding_event}).
 *
 * <p>Deliberately has <strong>no foreign key</strong> to the cluster config so it
 * survives offboarding (FR-8.2); {@link #clusterConfigId} is informational and may
 * point to a deleted row. {@link #detailJson} must never contain a token or key
 * (a unit test asserts this). Event types are listed in design §2.3.</p>
 */
@Entity
@Table(name = "cluster_onboarding_event")
public class ClusterOnboardingEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_id")
    private Long clientId;

    /** cloud_account.id — never the AWS account number (audit finding B4). */
    @Column(name = "cloud_account_pk", nullable = false)
    private Long cloudAccountPk;

    @Column(name = "cluster_name", nullable = false, length = 255)
    private String clusterName;

    @Column(name = "cluster_config_id")
    private Long clusterConfigId;

    @Column(name = "event_type", nullable = false, length = 40)
    private String eventType;

    /** USER | INSTALLER | AGENT | SYSTEM */
    @Column(name = "actor_type", nullable = false, length = 16)
    private String actorType;

    @Column(name = "actor_user_id")
    private Long actorUserId;

    @Column(name = "detail_json", columnDefinition = "TEXT")
    private String detailJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getClientId() { return clientId; }
    public void setClientId(Long clientId) { this.clientId = clientId; }

    public Long getCloudAccountPk() { return cloudAccountPk; }
    public void setCloudAccountPk(Long cloudAccountPk) { this.cloudAccountPk = cloudAccountPk; }

    public String getClusterName() { return clusterName; }
    public void setClusterName(String clusterName) { this.clusterName = clusterName; }

    public Long getClusterConfigId() { return clusterConfigId; }
    public void setClusterConfigId(Long clusterConfigId) { this.clusterConfigId = clusterConfigId; }

    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }

    public String getActorType() { return actorType; }
    public void setActorType(String actorType) { this.actorType = actorType; }

    public Long getActorUserId() { return actorUserId; }
    public void setActorUserId(Long actorUserId) { this.actorUserId = actorUserId; }

    public String getDetailJson() { return detailJson; }
    public void setDetailJson(String detailJson) { this.detailJson = detailJson; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
