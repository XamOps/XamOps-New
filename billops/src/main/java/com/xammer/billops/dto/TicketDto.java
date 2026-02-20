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
    private Long clientId;
    private long lastUpdated;

    private String category;
    private String service;
    private String severity;
    private String accountId;
    private String region;

    private List<TicketReplyDto> replies;

    private Long creatorId;

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
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public List<TicketReplyDto> getReplies() { return replies; }
    public void setReplies(List<TicketReplyDto> replies) { this.replies = replies; }
    public Long getCreatorId() { return creatorId; }
    public void setCreatorId(Long creatorId) { this.creatorId = creatorId; }
}