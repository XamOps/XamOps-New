package com.xammer.billops.dto;

import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;
import java.util.List;

public class TicketDto {

    private Long id;
    private String subject;
    private String description;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long clientId;
    private long lastUpdated;

    private String category;
    private String service;
    private String severity;
    private String accountId;
    private String accountName;
    private String region;

    private List<TicketReplyDto> replies;

    private Long creatorId;
    private String creatorUsername;
    private String creatorEmail;

    private LocalDateTime closedAt;
    private LocalDateTime chatStartedAt;
    private Long assignedToId;
    private String assignedToUsername;
    private boolean hasUnreadUserReply;
    private boolean hasUnreadAdminReply;

    /** WEB when raised through the app, EMAIL when ingested from the support mailbox. */
    private String source;

    /** Populated only for EMAIL tickets — the mail that opened this ticket. */
    private TicketEmailDetailsDto emailDetails;

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public TicketEmailDetailsDto getEmailDetails() {
        return emailDetails;
    }

    public void setEmailDetails(TicketEmailDetailsDto emailDetails) {
        this.emailDetails = emailDetails;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public Long getClientId() { return clientId; }
    public void setClientId(Long clientId) { this.clientId = clientId; }
    public long getLastUpdated() { return lastUpdated; }
    public void setLastUpdated(long lastUpdated) { this.lastUpdated = lastUpdated; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getService() { return service; }
    public void setService(String service) { this.service = service; }
    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }
    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }
    public String getAccountName() { return accountName; }
    public void setAccountName(String accountName) { this.accountName = accountName; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public List<TicketReplyDto> getReplies() { return replies; }
    public void setReplies(List<TicketReplyDto> replies) { this.replies = replies; }
    public Long getCreatorId() { return creatorId; }
    public void setCreatorId(Long creatorId) { this.creatorId = creatorId; }
    public String getCreatorUsername() { return creatorUsername; }
    public void setCreatorUsername(String creatorUsername) { this.creatorUsername = creatorUsername; }
    public String getCreatorEmail() { return creatorEmail; }
    public void setCreatorEmail(String creatorEmail) { this.creatorEmail = creatorEmail; }
    public LocalDateTime getClosedAt() { return closedAt; }
    public void setClosedAt(LocalDateTime closedAt) { this.closedAt = closedAt; }
    public LocalDateTime getChatStartedAt() { return chatStartedAt; }
    public void setChatStartedAt(LocalDateTime chatStartedAt) { this.chatStartedAt = chatStartedAt; }
    public Long getAssignedToId() { return assignedToId; }
    public void setAssignedToId(Long assignedToId) { this.assignedToId = assignedToId; }
    public String getAssignedToUsername() { return assignedToUsername; }
    public void setAssignedToUsername(String assignedToUsername) { this.assignedToUsername = assignedToUsername; }
    public boolean isHasUnreadUserReply() { return hasUnreadUserReply; }
    public void setHasUnreadUserReply(boolean hasUnreadUserReply) { this.hasUnreadUserReply = hasUnreadUserReply; }
    public boolean isHasUnreadAdminReply() { return hasUnreadAdminReply; }
    public void setHasUnreadAdminReply(boolean hasUnreadAdminReply) { this.hasUnreadAdminReply = hasUnreadAdminReply; }
}