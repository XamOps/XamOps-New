package com.xammer.billops.domain;

import lombok.Data;
import lombok.NoArgsConstructor;
import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Stores the platform connection details for a specific user on a specific
 * messaging platform.
 *
 * How each platform uses this:
 *
 * SLACK:
 * - webhookUrl → User's Slack channel Incoming Webhook URL (for outbound
 * messages)
 * - channelId → Slack channel/DM ID (used to correlate inbound Events API
 * messages back to this user)
 * - platformUserId → Slack member ID of the user (U0123ABC)
 *
 * TEAMS:
 * - webhookUrl → Microsoft Teams channel Incoming Webhook URL (for outbound
 * Adaptive Cards)
 * - channelId → Teams channel ID (for inbound correlation)
 *
 * GOOGLE_CHAT:
 * - webhookUrl → Google Chat Space Incoming Webhook URL (for outbound messages)
 * - channelId → Space name e.g. "spaces/XXXXXXX" (for inbound correlation)
 */
@Entity
@Data
@NoArgsConstructor
@Table(name = "user_platform_connection", uniqueConstraints = @UniqueConstraint(columnNames = { "user_id",
        "platform" }))
public class UserPlatformConnection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /**
     * Platform: SLACK | TEAMS | GOOGLE_CHAT
     */
    @Column(name = "platform", nullable = false, length = 20)
    private String platform;

    /**
     * Incoming Webhook URL for outbound messages (all three platforms support
     * this).
     * Admin or user pastes this once. No OAuth required.
     */
    @Column(name = "webhook_url", length = 2000)
    private String webhookUrl;

    /**
     * Platform-specific channel/space ID — used to map inbound webhook events back
     * to a XamOps user.
     * Slack: channel ID (C...) | Teams: channel ID | Google Chat: space name
     */
    @Column(name = "channel_id", length = 500)
    private String channelId;

    /**
     * Platform-specific user ID — Slack member ID (U...), Teams AAD object ID,
     * GChat user email.
     */
    @Column(name = "platform_user_id", length = 500)
    private String platformUserId;

    /**
     * Per-user Slack bot token obtained via OAuth 2.0 install flow.
     * When present, this token is used instead of the global bot token,
     * enabling cross-org messaging (user can be in any Slack workspace).
     */
    @Column(name = "user_bot_token", length = 500)
    private String userBotToken;

    /**
     * Slack workspace/team ID (T...) — populated during OAuth install.
     * Used to route inbound Events API payloads to the correct user.
     */
    @Column(name = "workspace_id", length = 100)
    private String workspaceId;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getPlatform() {
        return platform;
    }

    public void setPlatform(String platform) {
        this.platform = platform;
    }

    public String getWebhookUrl() {
        return webhookUrl;
    }

    public void setWebhookUrl(String webhookUrl) {
        this.webhookUrl = webhookUrl;
    }

    public String getChannelId() {
        return channelId;
    }

    public void setChannelId(String channelId) {
        this.channelId = channelId;
    }

    public String getPlatformUserId() {
        return platformUserId;
    }

    public void setPlatformUserId(String platformUserId) {
        this.platformUserId = platformUserId;
    }

    public String getUserBotToken() {
        return userBotToken;
    }

    public void setUserBotToken(String userBotToken) {
        this.userBotToken = userBotToken;
    }

    public String getWorkspaceId() {
        return workspaceId;
    }

    public void setWorkspaceId(String workspaceId) {
        this.workspaceId = workspaceId;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
