package com.xammer.billops.domain;

import lombok.Data;
import lombok.NoArgsConstructor;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Data
@NoArgsConstructor
@Table(name = "support_message")
public class SupportMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(nullable = false)
    private LocalDateTime timestamp;

    @Column(nullable = false)
    private boolean isUserMessage; 

    @Column(nullable = false)
    private String status; 

    @Column(name = "user_id")
    private Long userId; 

    @Column(name = "account_id")
    private String accountId;

    @Column(name = "account_name")
    private String accountName;

    @Column(name = "cloud_provider")
    private String cloudProvider;

    @Column(name = "sender_username")
    private String senderUsername;

    @Column(name = "attachment_url", length = 500)
    private String attachmentUrl;

    @Column(name = "attachment_name")
    private String attachmentName;

    @Column(name = "attachment_type", length = 100)
    private String attachmentType;

    @Column(name = "attachment_size")
    private Long attachmentSize;

    // Edit / delete (WhatsApp-style)
    @Column(name = "is_edited", nullable = false)
    private boolean isEdited = false;

    @Column(name = "edited_at")
    private LocalDateTime editedAt;

    @Column(name = "is_deleted", nullable = false)
    private boolean isDeleted = false;

    // External platform routing (set when admin sends via Slack/Teams/Google Chat)
    @Column(name = "external_platform", length = 20)
    private String externalPlatform; // SLACK, TEAMS, GOOGLE_CHAT

    @Column(name = "external_message_id", length = 500)
    private String externalMessageId; // Message TS (Slack) / activity ID (Teams) / message name (GChat)

    // Auto-ping settings (set by admin when initiating a conversation)
    @Column(name = "auto_ping_enabled", nullable = false)
    private boolean autoPingEnabled = false;

    // Comma-separated channel list: "WHATSAPP,SMS,EMAIL"
    @Column(name = "auto_ping_channels", length = 100)
    private String autoPingChannels;

    @Column(name = "auto_ping_sent", nullable = false)
    private boolean autoPingSent = false;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }
    public boolean isUserMessage() { return isUserMessage; }
    public void setUserMessage(boolean userMessage) { isUserMessage = userMessage; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }
    public String getAccountName() { return accountName; }
    public void setAccountName(String accountName) { this.accountName = accountName; }
    public String getCloudProvider() { return cloudProvider; }
    public void setCloudProvider(String cloudProvider) { this.cloudProvider = cloudProvider; }
    public String getSenderUsername() { return senderUsername; }
    public void setSenderUsername(String senderUsername) { this.senderUsername = senderUsername; }
    public String getAttachmentUrl() { return attachmentUrl; }
    public void setAttachmentUrl(String attachmentUrl) { this.attachmentUrl = attachmentUrl; }
    public String getAttachmentName() { return attachmentName; }
    public void setAttachmentName(String attachmentName) { this.attachmentName = attachmentName; }
    public String getAttachmentType() { return attachmentType; }
    public void setAttachmentType(String attachmentType) { this.attachmentType = attachmentType; }
    public Long getAttachmentSize() { return attachmentSize; }
    public void setAttachmentSize(Long attachmentSize) { this.attachmentSize = attachmentSize; }
    public String getExternalPlatform() { return externalPlatform; }
    public void setExternalPlatform(String externalPlatform) { this.externalPlatform = externalPlatform; }
    public String getExternalMessageId() { return externalMessageId; }
    public void setExternalMessageId(String externalMessageId) { this.externalMessageId = externalMessageId; }
    public boolean isAutoPingEnabled() { return autoPingEnabled; }
    public void setAutoPingEnabled(boolean autoPingEnabled) { this.autoPingEnabled = autoPingEnabled; }
    public String getAutoPingChannels() { return autoPingChannels; }
    public void setAutoPingChannels(String autoPingChannels) { this.autoPingChannels = autoPingChannels; }
    public boolean isAutoPingSent() { return autoPingSent; }
    public void setAutoPingSent(boolean autoPingSent) { this.autoPingSent = autoPingSent; }
    public boolean isEdited() { return isEdited; }
    public void setEdited(boolean edited) { isEdited = edited; }
    public LocalDateTime getEditedAt() { return editedAt; }
    public void setEditedAt(LocalDateTime editedAt) { this.editedAt = editedAt; }
    public boolean isDeleted() { return isDeleted; }
    public void setDeleted(boolean deleted) { isDeleted = deleted; }
}