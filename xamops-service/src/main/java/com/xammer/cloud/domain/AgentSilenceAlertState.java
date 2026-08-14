package com.xammer.cloud.domain;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.Table;
import java.time.LocalDateTime;

/**
 * One row per agent we have alerted about, so notifications fire on state transitions rather
 * than on every scan.
 *
 * Without persisted state the scan would re-notify every run for as long as a host stayed
 * silent, which trains people to ignore the alert. {@code resolvedAt} being null means the
 * alert is still open; it is stamped when the agent starts reporting again, which also drives
 * the recovery notification.
 */
@Entity
@Table(name = "agent_silence_alert_state",
        indexes = {
                @Index(name = "idx_agent_silence_open", columnList = "client_id,resolved_at"),
                @Index(name = "idx_agent_silence_host", columnList = "client_id,host_name")
        })
public class AgentSilenceAlertState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_id", nullable = false)
    private Long clientId;

    @Column(name = "provider_account_id", length = 100)
    private String providerAccountId;

    @Column(name = "host_name", nullable = false, length = 255)
    private String hostName;

    /** Status that opened the alert — OFFLINE or GONE. */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "last_seen_at")
    private LocalDateTime lastSeenAt;

    @Column(name = "alerted_at", nullable = false)
    private LocalDateTime alertedAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    public AgentSilenceAlertState() {
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getClientId() { return clientId; }
    public void setClientId(Long clientId) { this.clientId = clientId; }

    public String getProviderAccountId() { return providerAccountId; }
    public void setProviderAccountId(String providerAccountId) { this.providerAccountId = providerAccountId; }

    public String getHostName() { return hostName; }
    public void setHostName(String hostName) { this.hostName = hostName; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public LocalDateTime getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(LocalDateTime lastSeenAt) { this.lastSeenAt = lastSeenAt; }

    public LocalDateTime getAlertedAt() { return alertedAt; }
    public void setAlertedAt(LocalDateTime alertedAt) { this.alertedAt = alertedAt; }

    public LocalDateTime getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(LocalDateTime resolvedAt) { this.resolvedAt = resolvedAt; }
}
