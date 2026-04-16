package com.xammer.billops.domain;

import lombok.Data;
import lombok.NoArgsConstructor;
import javax.persistence.*;
import java.time.LocalDateTime;

/**
 * Stores per-tenant admin credentials for each messaging platform (Slack / Teams / Google Chat).
 * One row per platform per tenant schema.
 */
@Entity
@Data
@NoArgsConstructor
@Table(name = "messaging_integration_config")
public class MessagingIntegrationConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Platform identifier: SLACK | TEAMS | GOOGLE_CHAT
     */
    @Column(name = "platform", nullable = false, length = 20)
    private String platform;

    /**
     * Slack: Bot Token (xoxb-...)
     * Teams: not used (Teams uses per-user webhook URLs)
     * Google Chat: not used (GChat uses per-user webhook URLs)
     */
    @Column(name = "bot_token", length = 500)
    private String botToken;

    /**
     * Slack: Signing Secret — used to verify inbound webhook payloads (X-Slack-Signature).
     */
    @Column(name = "signing_secret", length = 300)
    private String signingSecret;

    /**
     * Human-readable label/description for this integration (e.g. "XamOps Workspace").
     */
    @Column(name = "label", length = 200)
    private String label;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = false;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }
    public String getBotToken() { return botToken; }
    public void setBotToken(String botToken) { this.botToken = botToken; }
    public String getSigningSecret() { return signingSecret; }
    public void setSigningSecret(String signingSecret) { this.signingSecret = signingSecret; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
