package com.xammer.cloud.domain;

import javax.persistence.*;
import java.time.LocalDateTime;

/**
 * Entity for storing agent events and audit log.
 * Tracks agent check-ins, data pushes, errors, and other events.
 */
@Entity
@Table(name = "agent_events",
    indexes = {
        @Index(name = "idx_agent_event_cluster", columnList = "cluster_config_id"),
        @Index(name = "idx_agent_event_type", columnList = "event_type"),
        @Index(name = "idx_agent_event_created", columnList = "created_at")
    }
)
public class AgentEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cluster_config_id", nullable = false)
    private Long clusterConfigId;

    /**
     * Type of event:
     * - checkin: Agent heartbeat
     * - push_success: Successful data push
     * - push_error: Failed data push
     * - startup: Agent started
     * - shutdown: Agent stopped
     * - config_change: Configuration changed
     */
    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @Column(name = "agent_version", length = 20)
    private String agentVersion;

    /**
     * Number of items received (for push events)
     */
    @Column(name = "items_received")
    private Integer itemsReceived;

    /**
     * Additional details about the event
     */
    @Column(name = "details", columnDefinition = "TEXT")
    private String details;

    /**
     * Error message (for error events)
     */
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /**
     * IP address of the agent
     */
    @Column(name = "agent_ip", length = 45)
    private String agentIp;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    // Constructors
    public AgentEvent() {
        this.createdAt = LocalDateTime.now();
    }

    public AgentEvent(Long clusterConfigId, String eventType) {
        this();
        this.clusterConfigId = clusterConfigId;
        this.eventType = eventType;
    }

    public AgentEvent(Long clusterConfigId, String eventType, String agentVersion, Integer itemsReceived) {
        this(clusterConfigId, eventType);
        this.agentVersion = agentVersion;
        this.itemsReceived = itemsReceived;
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    // Getters and Setters
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

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getAgentVersion() {
        return agentVersion;
    }

    public void setAgentVersion(String agentVersion) {
        this.agentVersion = agentVersion;
    }

    public Integer getItemsReceived() {
        return itemsReceived;
    }

    public void setItemsReceived(Integer itemsReceived) {
        this.itemsReceived = itemsReceived;
    }

    public String getDetails() {
        return details;
    }

    public void setDetails(String details) {
        this.details = details;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getAgentIp() {
        return agentIp;
    }

    public void setAgentIp(String agentIp) {
        this.agentIp = agentIp;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
